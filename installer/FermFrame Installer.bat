@echo off
rem Starts the FermFrame Installer window.
start "" powershell.exe -NoProfile -ExecutionPolicy Bypass -STA -WindowStyle Hidden -File "%~dp0files\FermFrame-Installer.ps1"
