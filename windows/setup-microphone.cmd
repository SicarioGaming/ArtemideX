@echo off
rem Double-click this to set up the PC for Starwire's microphone. It runs setup-microphone.ps1
rem without changing PowerShell's script policy, and that script asks for administrator rights.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-microphone.ps1" %*
