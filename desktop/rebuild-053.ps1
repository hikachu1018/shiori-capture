param([string]$Python)

$ErrorActionPreference = 'Stop'
if (-not $Python) {
  $Python = (& py -3.12 -c 'import sys; print(sys.executable)' | Select-Object -Last 1)
  if ($LASTEXITCODE -ne 0 -or -not $Python) { throw 'Python 3.12が見つかりません。-Python にpython.exeのフルパスを指定してください。' }
}
if (-not (Test-Path -LiteralPath $Python)) { throw "Pythonが見つかりません: $Python" }
& $Python -c 'import tkinter as tk; root=tk.Tk(); root.withdraw(); root.destroy()'
if ($LASTEXITCODE -ne 0) { throw '選択したPythonでTcl/Tkを起動できません。' }

$venv = Join-Path $PSScriptRoot '.venv-release-053'
$venvPython = Join-Path $venv 'Scripts\python.exe'
if (-not (Test-Path -LiteralPath $venvPython)) {
  & $Python -m venv $venv
  if ($LASTEXITCODE -ne 0) { throw 'ビルド用の仮想環境を作成できませんでした。' }
}
& $venvPython -m pip install --upgrade pip
if ($LASTEXITCODE -ne 0) { throw 'pipの更新に失敗しました。' }
& $venvPython -m pip install -r (Join-Path $PSScriptRoot 'requirements.txt')
if ($LASTEXITCODE -ne 0) { throw 'PC版の依存パッケージを導入できませんでした。' }

$env:SHIORI_PYTHON = $venvPython
& (Join-Path $PSScriptRoot 'build.ps1')
if ($LASTEXITCODE -ne 0) { throw 'PC版のビルドに失敗しました。' }
Write-Output "完成: $(Join-Path $PSScriptRoot 'dist\ShioriCapture-0.5.3-windows.zip')"
