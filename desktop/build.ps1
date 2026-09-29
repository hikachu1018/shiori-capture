$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$python = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $python)) { throw '先にPython 3.12で desktop/.venv を作成してください。' }
$env:PYTHONPATH = $PSScriptRoot
$env:PADDLE_PDX_CACHE_HOME = Join-Path $PSScriptRoot '.cache'
$env:PYINSTALLER_CONFIG_DIR = Join-Path $PSScriptRoot '.cache\pyinstaller'
$env:DISABLE_MODEL_SOURCE_CHECK = 'True'
$env:PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK = 'True'
& $python -c "from PIL import Image; from shiori.capture import JapaneseOcr; JapaneseOcr().read(Image.new('RGB',(300,180),'white'),False)"
if ($LASTEXITCODE -ne 0) { throw 'OCRモデルの準備に失敗しました。' }
$model = Join-Path $env:PADDLE_PDX_CACHE_HOME 'official_models'
& $python -m PyInstaller --noconfirm --clean --windowed --name ShioriCapture --distpath (Join-Path $PSScriptRoot 'dist') --workpath (Join-Path $PSScriptRoot 'build') --specpath $PSScriptRoot --paths $PSScriptRoot --collect-all paddle --collect-all paddlex --collect-all paddleocr --collect-all uiautomation --copy-metadata imagesize --copy-metadata opencv-contrib-python --copy-metadata pyclipper --copy-metadata pypdfium2 --copy-metadata python-bidi --copy-metadata shapely --add-data "$model;models/official_models" (Join-Path $PSScriptRoot 'launcher.py')
if ($LASTEXITCODE -ne 0) { throw 'Windows版のビルドに失敗しました。' }
$zip = Join-Path $PSScriptRoot 'dist\ShioriCapture-0.5.0-windows.zip'
Compress-Archive -Path (Join-Path $PSScriptRoot 'dist\ShioriCapture') -DestinationPath $zip -Force
Get-FileHash -Algorithm SHA256 -LiteralPath $zip
