# 1. Lee las variables del archivo .env
Get-Content ".env" | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]+)=(.*)$') {
        [System.Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
    }
}

$env:JAVA_HOME="C:\Program Files\Java\jdk-17"
$env:SPRING_PROFILES_ACTIVE="prod"

Write-Host "Compilando la aplicación para producción..." -ForegroundColor Cyan
.\mvnw.cmd clean package -DskipTests

Write-Host "Iniciando servidor de producción..." -ForegroundColor Green
java -jar target/biblioteca-api-1.0.0.jar
