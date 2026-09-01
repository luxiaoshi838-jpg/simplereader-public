from pathlib import Path
p=Path('tools/apply-v754.py')
s=p.read_text()
a=s.index("d = 'app/src/main/java/com/simplereader/app/reader/DirectTxtCatalogV100.kt'")
b=s.index("# Reader behavior.", a)
segment=s[a:b]
segment=segment.replace(r'\\s', r'\\\\s').replace(r'\\p', r'\\\\p')
p.write_text(s[:a]+segment+s[b:])
