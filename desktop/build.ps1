$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$python = if ($env:SHIORI_PYTHON) { $env:SHIORI_PYTHON } else { Join-Path $PSScriptRoot '.venv\Scripts\python.exe' }
if (-not (Test-Path -LiteralPath $python)) { throw "ビルド用Pythonがありません: $python" }
$env:PYTHONPATH = $PSScriptRoot
$buildCache = if ($env:SHIORI_BUILD_CACHE) { $env:SHIORI_BUILD_CACHE } else { Join-Path $PSScriptRoot '.cache' }
$env:PADDLE_PDX_CACHE_HOME = $buildCache
$env:PYINSTALLER_CONFIG_DIR = Join-Path $buildCache 'pyinstaller'
$env:DISABLE_MODEL_SOURCE_CHECK = 'True'
$env:PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK = 'True'
# PyInstaller silently excludes tkinter when Tcl/Tk cannot initialize in its
# build Python. Check the actual interpreter before spending time on OCR models.
& $python -c "import tkinter as tk; root = tk.Tk(); root.withdraw(); root.destroy()"
if ($LASTEXITCODE -ne 0) { throw 'このPython環境ではTcl/Tkを起動できません。Tcl/Tk付きの完全なPython 3.12でdesktop/.venvを作り直してください。' }
& $python -c "from PIL import Image; from shiori.capture import JapaneseOcr; JapaneseOcr().read(Image.new('RGB',(300,180),'white'),False)"
if ($LASTEXITCODE -ne 0) { throw 'OCRモデルの準備に失敗しました。' }
$model = Join-Path $env:PADDLE_PDX_CACHE_HOME 'official_models'
& $python -m PyInstaller --noconfirm --clean --windowed --name ShioriCapture --distpath (Join-Path $PSScriptRoot 'dist') --workpath (Join-Path $PSScriptRoot 'build') --specpath $PSScriptRoot --paths $PSScriptRoot --hidden-import tkinter --hidden-import _tkinter --collect-all paddle --collect-all paddlex --collect-all paddleocr --collect-all uiautomation --copy-metadata imagesize --copy-metadata opencv-contrib-python --copy-metadata pyclipper --copy-metadata pypdfium2 --copy-metadata python-bidi --copy-metadata shapely --add-data "$model;models/official_models" (Join-Path $PSScriptRoot 'launcher.py')
if ($LASTEXITCODE -ne 0) { throw 'Windows版のビルドに失敗しました。' }
$guiReport = Join-Path $PSScriptRoot ("build\verify-gui-$([guid]::NewGuid()).txt")
& (Join-Path $PSScriptRoot 'dist\ShioriCapture\ShioriCapture.exe') --verify-gui $guiReport
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $guiReport) -or -not ((Get-Content -LiteralPath $guiReport -Raw) -match 'Tkinter GUI ready')) {
 throw "配布用EXEでGUIを起動できません。$guiReport を確認してください。"
}
$zip = Join-Path $PSScriptRoot 'dist\ShioriCapture-0.5.3-windows.zip'
Compress-Archive -Path (Join-Path $PSScriptRoot 'dist\ShioriCapture') -DestinationPath $zip -Force
Get-FileHash -Algorithm SHA256 -LiteralPath $zip
