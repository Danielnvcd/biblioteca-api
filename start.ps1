# Lee las variables del archivo .env
Get-Content ".env" | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]+)=(.*)$') {
        [System.Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
    }
}

$env:JAVA_HOME="C:\Program Files\Java\jdk-17"
.\mvnw.cmd clean spring-boot:run
