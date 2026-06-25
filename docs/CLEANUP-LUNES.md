# Limpieza pendiente — para el lunes 2026-06-08

Después de validar que prod siguió operando bien todo el fin de semana con
X-Accel-Redirect activo, borrar lo que quedó de la migración del viernes 5.

## Qué borrar

```bash
# 1. Backup de los uploads pre-migración (52M)
sudo rm -rf /srv/biblioteca-uploads-backup-20260605_105618

# 2. Volumen Docker viejo (51.2M) — los archivos están en bind-mount ahora
docker volume rm biblioteca-api_uploads
```

## Verificación

```bash
# /srv/ solo debe tener biblioteca-uploads (sin -backup)
sudo ls /srv/ | grep biblioteca

# No debe haber volumen llamado biblioteca-api_uploads
docker volume ls | grep upload

# Las imágenes siguen cargando desde el frontend
curl -I "https://api.bibliotecamaxipet.cloud/api/files/perfiles/$(ls /srv/biblioteca-uploads/perfiles/ | head -1)"
# Esperado: HTTP/2 200, content-type: image/jpeg
```

## NO borrar

- `~/.cloudflared/cert.pem` — auth de tu cuenta de Cloudflare
- `~/.cloudflared/fb72c0e4-*.json` — credenciales del Tunnel del API
- `/srv/biblioteca-uploads/` — los archivos reales (bind-mount activo)
