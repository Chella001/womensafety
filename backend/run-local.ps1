# Starts the SafeHer backend on http://localhost:8080 for local development.
# Usage (PowerShell):   cd backend ; .\run-local.ps1
#
# Contains NO secrets: it only points at the service-account key kept OUTSIDE the project.

$ErrorActionPreference = "Stop"

$keyFile = "C:\secrets\safeher-firebase.json"
if (-not (Test-Path $keyFile)) {
    throw "Service-account key not found at $keyFile. Download it from Firebase console > Project settings > Service accounts."
}

if (-not $env:JAVA_HOME -and (Test-Path "C:\Program Files\Java\jdk-21.0.9")) {
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.9"
}

$env:FIREBASE_PROJECT_ID = "womensafety-f7fae"
$env:FIREBASE_CREDENTIALS_PATH = $keyFile
# This project's Firestore database was created with the id "default" (not "(default)").
$env:FIREBASE_FIRESTORE_DATABASE_ID = "default"

Set-Location $PSScriptRoot
.\mvnw.cmd spring-boot:run
