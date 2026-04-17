# Create gradle/wrapper directory
New-Item -ItemType Directory -Force -Path "gradle\wrapper" | Out-Null

# Download gradlew.bat
Write-Host "Downloading gradlew.bat..." -ForegroundColor Cyan
Invoke-WebRequest -Uri "https://raw.githubusercontent.com/gradle/gradle/v8.1.0/gradlew.bat" -OutFile "gradlew.bat"

# Download gradlew
Write-Host "Downloading gradlew..." -ForegroundColor Cyan
Invoke-WebRequest -Uri "https://raw.githubusercontent.com/gradle/gradle/v8.1.0/gradlew" -OutFile "gradlew"

# Download gradle-wrapper.jar
Write-Host "Downloading gradle-wrapper.jar..." -ForegroundColor Cyan
Invoke-WebRequest -Uri "https://raw.githubusercontent.com/gradle/gradle/v8.1.0/gradle/wrapper/gradle-wrapper.jar" -OutFile "gradle\wrapper\gradle-wrapper.jar"

Write-Host "Wrapper generated successfully! Now running the build..." -ForegroundColor Green

# Run the build
.\gradlew.bat assembleDebug

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "Build complete! You can find the APK in:" -ForegroundColor Green
Write-Host "app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Green