@echo off
rem Arrête l'essai d'ORPEX ; les données sont conservées pour le prochain lancement.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\essai.ps1" -Arreter
