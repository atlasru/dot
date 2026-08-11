from pathlib import Path
p = Path('app/src/main/java/dev/dotclient/android/ui/DotApp.kt')
s = p.read_text()
s = s.replace('        AboutLink("license", "open source") { openUrl("https://github.com/atlasru/dot/blob/main/LICENSE") }\n', '')
p.write_text(s)
