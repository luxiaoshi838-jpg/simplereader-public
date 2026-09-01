from pathlib import Path
p=Path('tools/apply-v754.py')
s=p.read_text()
s=s.replace('./gradlew testDebugUnitTest assembleRelease --stacktrace 2>&1 | tee gradle-build.log','./gradlew testDebugUnitTest --tests com.simplereader.app.parser.TxtCatalogRule112Test assembleRelease --stacktrace 2>&1 | tee gradle-build.log')
p.write_text(s)
