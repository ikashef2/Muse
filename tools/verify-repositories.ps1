$ErrorActionPreference = "Stop"

$checks = @(
    "https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.0.1/gradle-9.0.1.pom",
    "https://repo.maven.apache.org/maven2/androidx/core/core-ktx/1.15.0/core-ktx-1.15.0.pom",
    "https://plugins.gradle.org/m2/org/jetbrains/kotlin/plugin/compose/org.jetbrains.kotlin.plugin.compose.gradle.plugin/2.2.10/org.jetbrains.kotlin.plugin.compose.gradle.plugin-2.2.10.pom",
    "https://services.gradle.org/distributions/gradle-9.1.0-bin.zip"
)

Write-Host "Archive dependency connectivity check" -ForegroundColor Cyan
foreach ($url in $checks) {
    try {
        $response = Invoke-WebRequest -Uri $url -Method Head -TimeoutSec 30 -UseBasicParsing
        Write-Host "OK  $($response.StatusCode)  $url" -ForegroundColor Green
    }
    catch {
        Write-Host "FAIL  $url" -ForegroundColor Red
        Write-Host "      $($_.Exception.Message)" -ForegroundColor DarkRed
    }
}

Write-Host "`nIf dl.google.com fails, Gradle cannot resolve the Android plugin." -ForegroundColor Yellow
Write-Host "Disable Gradle Offline Mode and use a network route that can reach Google's Maven repository."
