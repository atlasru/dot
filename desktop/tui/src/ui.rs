// SPDX-License-Identifier: GPL-3.0-or-later
use ratatui::{
    layout::{Alignment, Constraint, Layout, Rect},
    style::{Color, Modifier, Style},
    text::{Line, Span},
    widgets::{
        Block, BorderType, Borders, Cell, Clear, HighlightSpacing, Paragraph, Row, Table,
        TableState, Wrap,
    },
    Frame,
};

use crate::model::{EnginePhase, EngineSnapshot, GroupView, NodeSortMode, NodeView};

const BG: Color = Color::Rgb(13, 17, 23);
const PANEL: Color = Color::Rgb(18, 24, 33);
const TEXT: Color = Color::Rgb(219, 228, 239);
const MUTED: Color = Color::Rgb(141, 157, 178);
const BORDER: Color = Color::Rgb(52, 67, 86);
const ACCENT: Color = Color::Rgb(101, 200, 224);
const SELECTED: Color = Color::Rgb(30, 57, 76);
const GREEN: Color = Color::Rgb(125, 211, 164);
const YELLOW: Color = Color::Rgb(235, 193, 112);
const RED: Color = Color::Rgb(239, 138, 138);

#[derive(Default)]
pub struct ViewState {
    table: TableState,
    group: Option<usize>,
}

pub struct View<'a> {
    pub groups: &'a [GroupView],
    pub group: usize,
    pub row: usize,
    pub snapshot: &'a EngineSnapshot,
    pub message: &'a str,
    pub input_len: Option<usize>,
    pub busy: bool,
    pub show_help: bool,
}

fn panel(title: String) -> Block<'static> {
    Block::default()
        .borders(Borders::ALL)
        .border_type(BorderType::Rounded)
        .border_style(Style::default().fg(BORDER))
        .style(Style::default().fg(TEXT).bg(PANEL))
        .title(Line::from(title).style(Style::default().fg(ACCENT)))
}

/// Regional-indicator emoji have inconsistent widths/fonts in Windows terminals.
/// Render their country letters instead, and never pass terminal controls through.
fn display_text(value: &str) -> String {
    value
        .chars()
        .filter_map(|c| match c {
            '\u{1f1e6}'..='\u{1f1ff}' => char::from_u32('A' as u32 + c as u32 - 0x1f1e6),
            '\u{fe0e}'
            | '\u{fe0f}'
            | '\u{200d}'
            | '\u{202a}'..='\u{202e}'
            | '\u{2066}'..='\u{2069}' => None,
            c if c.is_control() => Some(' '),
            c => Some(c),
        })
        .collect()
}

fn fit(value: &str, width: u16) -> String {
    let value = display_text(value);
    if Line::from(value.as_str()).width() <= usize::from(width) {
        return value;
    }
    if width == 0 {
        return String::new();
    }
    let mut result = String::new();
    let mut used = 0;
    for c in value.chars() {
        let cell_width = Span::raw(c.to_string()).width();
        if used + cell_width > usize::from(width - 1) {
            break;
        }
        result.push(c);
        used += cell_width;
    }
    result.push('…');
    result
}

fn phase(snapshot: &EngineSnapshot) -> (&'static str, Color) {
    match snapshot.phase {
        EnginePhase::Connected => ("ПОДКЛЮЧЕНО", GREEN),
        EnginePhase::Starting => ("ПОДКЛЮЧЕНИЕ", YELLOW),
        EnginePhase::Stopping => ("ОТКЛЮЧЕНИЕ", YELLOW),
        EnginePhase::Error => ("ОШИБКА", RED),
        EnginePhase::Offline => ("НЕ ПОДКЛЮЧЕНО", MUTED),
    }
}

fn sort_name(sort: NodeSortMode) -> &'static str {
    match sort {
        NodeSortMode::Origin => "исходный",
        NodeSortMode::Name => "по имени",
        NodeSortMode::Delay => "по пингу",
    }
}

pub fn draw(frame: &mut Frame<'_>, view: &View<'_>, state: &mut ViewState) {
    let screen = frame.area();
    frame.render_widget(
        Block::default().style(Style::default().bg(BG).fg(TEXT)),
        screen,
    );
    if screen.width < 44 || screen.height < 12 {
        frame.render_widget(
            Paragraph::new("dot. TUI\nУвеличьте окно до 44 × 12\nq — выход")
                .style(Style::default().fg(TEXT).bg(BG))
                .wrap(Wrap { trim: true }),
            screen,
        );
        return;
    }
    let width = screen.width.saturating_sub(2).min(132);
    let margin_y = u16::from(screen.height >= 22);
    let area = Rect::new(
        screen.x + (screen.width - width) / 2,
        screen.y + margin_y,
        width,
        screen.height - margin_y * 2,
    );
    let details_height = if area.height >= 22 { 4 } else { 0 };
    let sections = Layout::vertical([
        Constraint::Length(3),
        Constraint::Min(4),
        Constraint::Length(details_height),
        Constraint::Length(3),
        Constraint::Length(2),
    ])
    .split(area);

    let (phase_text, phase_color) = phase(view.snapshot);
    let header = panel(" dot. / TERMINAL ".into());
    let header_inner = header.inner(sections[0]);
    frame.render_widget(header, sections[0]);
    let header_cols =
        Layout::horizontal([Constraint::Min(1), Constraint::Length(18)]).split(header_inner);
    let active = view
        .snapshot
        .node_name
        .as_deref()
        .unwrap_or("Выберите узел и нажмите Enter");
    frame.render_widget(
        Paragraph::new(fit(active, header_cols[0].width)).style(Style::default().fg(TEXT)),
        header_cols[0],
    );
    frame.render_widget(
        Paragraph::new(phase_text)
            .alignment(Alignment::Right)
            .style(
                Style::default()
                    .fg(phase_color)
                    .add_modifier(Modifier::BOLD),
            ),
        header_cols[1],
    );

    let group = view.groups.get(view.group);
    let node = group.and_then(|g| g.nodes.get(view.row.min(g.nodes.len().saturating_sub(1))));
    if state.group != Some(view.group) {
        state.table = TableState::default();
        state.group = Some(view.group);
    }
    state.table.select(
        group
            .filter(|g| !g.nodes.is_empty())
            .map(|g| view.row.min(g.nodes.len() - 1)),
    );
    let title = group
        .map(|g| format!(" {}  /  {} узлов ", display_text(&g.name), g.nodes.len()))
        .unwrap_or_else(|| " Подписки ".into());
    let block = panel(fit(&title, sections[1].width.saturating_sub(4)));
    let inner = block.inner(sections[1]);
    if let Some(group) = group.filter(|g| !g.nodes.is_empty()) {
        draw_nodes(frame, view, group, block, sections[1], state);
    } else {
        frame.render_widget(block, sections[1]);
        frame.render_widget(
            Paragraph::new("\nПока нет узлов\n\na — добавить подписку   r — обновить")
                .alignment(Alignment::Center)
                .style(Style::default().fg(MUTED))
                .wrap(Wrap { trim: true }),
            inner,
        );
    }

    if details_height > 0 {
        let lines = node
            .map(|n| {
                vec![
                    Line::from(Span::styled(
                        fit(&n.name, sections[2].width.saturating_sub(2)),
                        Style::default().fg(TEXT),
                    )),
                    Line::from(Span::styled(
                        fit(
                            &format!(
                                "{}:{}  /  {}  /  {:?}  /  {:?}",
                                n.host, n.port, n.protocol, n.transport, n.security
                            ),
                            sections[2].width.saturating_sub(2),
                        ),
                        Style::default().fg(MUTED),
                    )),
                ]
            })
            .unwrap_or_else(|| vec![Line::from("Добавьте подписку, чтобы выбрать сервер")]);
        frame.render_widget(
            Paragraph::new(lines).block(panel(" Выбранный узел ".into())),
            sections[2],
        );
    }
    let message = if let Some(length) = view.input_len {
        format!("URL скрыт: введено {length} символов. Enter — добавить, Esc — отменить")
    } else {
        display_text(view.message)
    };
    let engine_message = view.snapshot.message.as_deref().map(display_text);
    let status = if view.busy {
        format!("{message}  ·  выполняется…")
    } else {
        message
    };
    let status = engine_message
        .filter(|m| !m.is_empty())
        .map(|m| format!("{status}  /  {m}"))
        .unwrap_or(status);
    frame.render_widget(
        Paragraph::new(fit(&status, sections[3].width.saturating_sub(2)))
            .block(panel(" Состояние ".into()))
            .style(Style::default().fg(MUTED)),
        sections[3],
    );

    let help = if width >= 100 {
        "Enter подключить  d отключить  t пинг  Tab группа  a добавить  r обновить  ? помощь  q выход"
    } else if width >= 64 {
        "Enter подключить  d отключить  Tab группа  ? помощь  q выход"
    } else {
        "Enter VPN   j/k узлы   ? помощь   q выход"
    };
    let position = group
        .map(|g| {
            format!(
                "{}/{} · группа {}/{} · порядок: {}",
                if g.nodes.is_empty() {
                    0
                } else {
                    view.row.min(g.nodes.len() - 1) + 1
                },
                g.nodes.len(),
                view.group + 1,
                view.groups.len(),
                sort_name(g.sort_mode)
            )
        })
        .unwrap_or_default();
    frame.render_widget(
        Paragraph::new(vec![
            Line::from(help).style(Style::default().fg(TEXT)),
            Line::from(fit(&position, width)).style(Style::default().fg(MUTED)),
        ]),
        sections[4],
    );
    if view.show_help {
        draw_help(frame, area);
    }
}

fn draw_nodes(
    frame: &mut Frame<'_>,
    view: &View<'_>,
    group: &GroupView,
    block: Block<'static>,
    area: Rect,
    state: &mut ViewState,
) {
    let show_host = area.width >= 90;
    let show_protocol = area.width >= 64;
    // Reserve border, selection marker and column gaps before assigning cells.
    let gaps = if show_host {
        6
    } else if show_protocol {
        4
    } else {
        2
    };
    let available = area.width.saturating_sub(4 + gaps);
    let latency_width = 9;
    let protocol_width = if show_protocol { 7 } else { 0 };
    let host_width = if show_host {
        (available / 3).min(36)
    } else {
        0
    };
    let name_width = available.saturating_sub(latency_width + protocol_width + host_width);
    let mut widths = vec![Constraint::Length(name_width)];
    let mut labels = vec![Cell::from("УЗЕЛ")];
    if show_protocol {
        widths.push(Constraint::Length(protocol_width));
        labels.push(Cell::from("ТИП"));
    }
    if show_host {
        widths.push(Constraint::Length(host_width));
        labels.push(Cell::from("АДРЕС"));
    }
    widths.push(Constraint::Length(latency_width));
    labels.push(Cell::from("ПИНГ"));
    let rows = group.nodes.iter().map(|n| {
        let current = view.snapshot.node_id.as_deref() == Some(n.id.as_str());
        let marker = if current {
            "> "
        } else if n.favorite {
            "* "
        } else {
            "  "
        };
        let mut cells = vec![Cell::from(fit(&format!("{marker}{}", n.name), name_width))];
        if show_protocol {
            cells.push(Cell::from(n.protocol).style(Style::default().fg(ACCENT)));
        }
        if show_host {
            cells.push(Cell::from(fit(&n.host, host_width)).style(Style::default().fg(MUTED)));
        }
        let (latency, color) = match n.latency_ms {
            Some(ms) => (
                format!("{ms} ms"),
                if ms < 200 {
                    GREEN
                } else if ms < 500 {
                    YELLOW
                } else {
                    RED
                },
            ),
            None if n.latency_failed => ("ошибка".into(), RED),
            None => ("—".into(), MUTED),
        };
        cells.push(
            Cell::from(Line::from(fit(&latency, latency_width)).alignment(Alignment::Right))
                .style(Style::default().fg(color)),
        );
        Row::new(cells).style(Style::default().fg(if current { GREEN } else { TEXT }))
    });
    let table = Table::new(rows, widths)
        .block(block)
        .column_spacing(2)
        .header(
            Row::new(labels)
                .height(1)
                .bottom_margin(1)
                .style(Style::default().fg(MUTED).add_modifier(Modifier::BOLD)),
        )
        .highlight_symbol("› ")
        .highlight_spacing(HighlightSpacing::Always)
        .row_highlight_style(Style::default().bg(SELECTED).add_modifier(Modifier::BOLD));
    frame.render_stateful_widget(table, area, &mut state.table);
}

fn draw_help(frame: &mut Frame<'_>, area: Rect) {
    let width = area.width.min(62);
    let height = area.height.min(19);
    let popup = Rect::new(
        area.x + (area.width - width) / 2,
        area.y + (area.height - height) / 2,
        width,
        height,
    );
    frame.render_widget(Clear, popup);
    let lines = vec![
        "j / k, стрелки       Выбрать узел",
        "PgUp / PgDn          Перейти на 10 узлов",
        "Home / End           Первый / последний узел",
        "Tab / Shift+Tab      Сменить подписку",
        "Enter                Подключить / переключить",
        "d                    Отключить VPN",
        "a                    Добавить подписку",
        "r                    Обновить подписку",
        "t                    Проверить пинг узла",
        "s                    Изменить порядок узлов",
        "q                    Выйти и остановить VPN",
        "",
        "Esc / ? / F1         Закрыть помощь",
    ];
    frame.render_widget(
        Paragraph::new(lines.into_iter().map(Line::from).collect::<Vec<_>>())
            .wrap(Wrap { trim: false })
            .block(panel(" Клавиши ".into())),
        popup,
    );
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{Security, Transport};
    use ratatui::{backend::TestBackend, buffer::Buffer, Terminal};

    fn group() -> GroupView {
        GroupView {
            id: "demo".into(),
            name: "hitori".into(),
            updated_at_ms: 0,
            sort_mode: NodeSortMode::Origin,
            remote: true,
            hwid: None,
            nodes: (0..30)
                .map(|i| NodeView {
                    id: i.to_string(),
                    name: format!(
                        "{} #{}",
                        ["🇷🇺 Россия", "🇪🇪 Эстония — GRPC", "🇫🇷 Франция — Hysteria2"][i % 3],
                        i + 1
                    ),
                    host: format!("server-{}.example.net", i + 1),
                    port: 443,
                    protocol: if i % 3 == 2 { "HY2" } else { "VLESS" },
                    security: Security::Tls,
                    transport: Transport::Raw,
                    latency_ms: if i % 4 == 0 {
                        None
                    } else {
                        Some(130 + i as u64 * 13)
                    },
                    latency_failed: i % 4 == 0,
                    favorite: i == 1,
                })
                .collect(),
        }
    }

    fn view<'a>(groups: &'a [GroupView], snapshot: &'a EngineSnapshot, row: usize) -> View<'a> {
        View {
            groups,
            snapshot,
            group: 0,
            row,
            message: "Готово. Enter — подключить выбранный узел.",
            input_len: None,
            busy: false,
            show_help: false,
        }
    }

    fn render(width: u16, height: u16, view: &View<'_>, state: &mut ViewState) -> Buffer {
        let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
        terminal.draw(|frame| draw(frame, view, state)).unwrap();
        terminal.backend().buffer().clone()
    }

    fn text(buffer: &Buffer) -> String {
        buffer
            .content()
            .chunks(usize::from(buffer.area.width))
            .map(|row| row.iter().map(|c| c.symbol()).collect::<String>())
            .collect::<Vec<_>>()
            .join("\n")
    }

    #[test]
    fn flags_and_terminal_controls_are_safe_and_truncation_uses_cell_width() {
        assert_eq!(
            display_text("🇷🇺 Россия 🇪🇪\n\u{1b}x\u{202e}"),
            "RU Россия EE  x"
        );
        for width in 0..20 {
            let fitted = fit("🇪🇪 Очень длинное имя 東京 e\u{301}", width);
            assert!(Line::from(fitted).width() <= usize::from(width));
        }
    }

    #[test]
    fn wide_table_aligns_columns_for_different_name_lengths() {
        let groups = [group()];
        let snapshot = EngineSnapshot::default();
        let buffer = render(
            132,
            40,
            &view(&groups, &snapshot, 1),
            &mut ViewState::default(),
        );
        let lines = text(&buffer);
        let data: Vec<_> = lines
            .lines()
            .filter(|line| line.contains("example.net"))
            .collect();
        assert!(data.len() >= 3);
        let columns: Vec<_> = data
            .iter()
            .take(3)
            .map(|line| line[..line.find("server-").unwrap()].chars().count())
            .collect();
        let header = lines.lines().find(|line| line.contains("АДРЕС")).unwrap();
        let address_column = header[..header.find("АДРЕС").unwrap()].chars().count();
        assert_eq!(columns, vec![address_column; 3]);
        assert!(lines.contains("RU Россия"));
        assert!(lines.contains("АДРЕС"));
        assert!(!lines.contains('🇷'));
    }

    #[test]
    fn narrow_table_keeps_names_ping_selection_and_help_visible() {
        let groups = [group()];
        let snapshot = EngineSnapshot::default();
        let lines = text(&render(
            60,
            18,
            &view(&groups, &snapshot, 29),
            &mut ViewState::default(),
        ));
        assert!(lines.contains("ПИНГ"));
        assert!(lines.contains("#30"));
        assert!(lines.contains("? помощь"));
        assert!(!lines.contains("АДРЕС"));
        assert!(!lines.contains("ТИП"));
    }

    #[test]
    fn scrolling_persists_and_tracks_selected_row() {
        let groups = [group()];
        let snapshot = EngineSnapshot::default();
        let mut state = ViewState::default();
        render(80, 24, &view(&groups, &snapshot, 29), &mut state);
        let offset = state.table.offset();
        assert!(offset > 0);
        render(80, 24, &view(&groups, &snapshot, 28), &mut state);
        assert_eq!(state.table.offset(), offset);
        assert_eq!(state.table.selected(), Some(28));
    }

    #[test]
    fn layouts_handle_resize_empty_groups_and_long_unicode_names() {
        let mut groups = [group()];
        groups[0].nodes[0].name = "🇪🇪 東京 e\u{301} Очень длинное название ".repeat(10);
        let snapshot = EngineSnapshot::default();
        for (width, height) in [
            (1, 1),
            (30, 8),
            (44, 12),
            (60, 18),
            (80, 24),
            (120, 32),
            (190, 42),
        ] {
            render(
                width,
                height,
                &view(&groups, &snapshot, 0),
                &mut ViewState::default(),
            );
            render(
                width,
                height,
                &view(&[], &snapshot, 0),
                &mut ViewState::default(),
            );
        }
    }

    #[test]
    fn help_overlay_and_masked_input_render_without_leaking_urls() {
        let groups = [group()];
        let snapshot = EngineSnapshot::default();
        let mut model = view(&groups, &snapshot, 0);
        model.show_help = true;
        let lines = text(&render(100, 30, &model, &mut ViewState::default()));
        assert!(lines.contains("Клавиши"));
        assert!(lines.contains("Home / End"));
        model.show_help = false;
        model.input_len = Some(42);
        let lines = text(&render(100, 30, &model, &mut ViewState::default()));
        assert!(lines.contains("URL скрыт: введено 42"));
    }

    #[test]
    fn export_layout_previews() {
        let Ok(directory) = std::env::var("DOT_TUI_PREVIEW_DIR") else {
            return;
        };
        std::fs::create_dir_all(&directory).unwrap();
        let groups = [group()];
        let snapshot = EngineSnapshot::default();
        for (label, width, height, row) in [
            ("wide", 132, 40, 6),
            ("normal", 80, 24, 1),
            ("narrow", 60, 18, 29),
        ] {
            let buffer = render(
                width,
                height,
                &view(&groups, &snapshot, row),
                &mut ViewState::default(),
            );
            let cells: Vec<_> = buffer.content().iter().map(|cell| serde_json::json!({
                "symbol": cell.symbol(), "fg": format!("{:?}", cell.fg), "bg": format!("{:?}", cell.bg),
                "bold": cell.modifier.contains(Modifier::BOLD),
            })).collect();
            let preview = serde_json::json!({"width": width, "height": height, "cells": cells});
            std::fs::write(
                std::path::Path::new(&directory).join(format!("{label}.json")),
                serde_json::to_vec(&preview).unwrap(),
            )
            .unwrap();
        }
    }
}
