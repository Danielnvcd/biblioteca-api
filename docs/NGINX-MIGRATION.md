# Migración a X-Accel-Redirect (uploads servidos por nginx)

Este documento describe cómo deployar el cambio de servir archivos vía
`X-Accel-Redirect` (nginx sendfile) en lugar de stream desde la JVM.

**Resumen del cambio:**
- Spring valida auth/roles y responde con header `X-Accel-Redirect` + body vacío.
- nginx intercepta el header, sirve el archivo directo desde disco.
- Requiere bind-mount del volumen `uploads` para que nginx (en el host) pueda
  leer los archivos que el container de Spring (Docker) escribe.

## Pre-requisitos en el VPS

- nginx ya configurado con el site `biblioteca-api` (Pasos 1 y 2 aplicados)
- Docker compose corriendo el stack actual con volumen `uploads` managed
- Acceso sudo

## Pasos de deploy

### 1. Crear el directorio del bind-mount con UID correcto

```bash
sudo mkdir -p /srv/biblioteca-uploads
sudo chown 1000:1000 /srv/biblioteca-uploads
sudo chmod 755 /srv/biblioteca-uploads
```

### 2. Identificar el volumen Docker actual

```bash
cd /ruta/al/repo  # donde está el docker-compose.yml
docker volume ls | grep upload
# Debe listar algo como: biblioteca-api_uploads
```

Guardá el nombre exacto del volumen para el siguiente paso.

### 3. Backup del volumen actual

⚠️ **No salteés este paso.** Si la migración sale mal, este backup es lo que
tenés para restaurar.

```bash
TS=$(date +%Y%m%d_%H%M%S)
sudo mkdir -p /srv/biblioteca-uploads-backup-$TS
docker run --rm \
  -v biblioteca-api_uploads:/from:ro \
  -v /srv/biblioteca-uploads-backup-$TS:/to \
  alpine sh -c 'cp -a /from/. /to/'

ls /srv/biblioteca-uploads-backup-$TS  # verificar que copió bien
```

> Ajustar `biblioteca-api_uploads` al nombre real del volumen del paso 2.

### 4. Detener la API y copiar al bind-mount

```bash
docker compose stop api

docker run --rm \
  -v biblioteca-api_uploads:/from:ro \
  -v /srv/biblioteca-uploads:/to \
  alpine sh -c 'cp -a /from/. /to/ && chown -R 1000:1000 /to'

ls /srv/biblioteca-uploads  # verificar
```

### 5. Pull del código nuevo y rebuild

```bash
git pull
docker compose up -d --build
```

Esto va a:
- Reconstruir la imagen con el FileController nuevo (X-Accel-Redirect activable)
- Usar el bind-mount de `/srv/biblioteca-uploads` en vez del volumen managed
- Activar `app.internal-redirect.enabled=true` por estar en perfil `prod`

### 6. Aplicar el config de nginx con la `location /_protected/`

```bash
sudo cp nginx/sites-available/biblioteca-api /etc/nginx/sites-available/biblioteca-api
sudo nginx -t && sudo systemctl reload nginx
```

### 7. Verificación

```bash
# (a) Spring arrancó y la API responde
docker compose ps   # api debe estar "Up (healthy)"
curl -I https://api.bibliotecamaxipet.cloud/actuator/health

# (b) Pedir un archivo público (perfil de usuario) y ver headers
curl -I https://api.bibliotecamaxipet.cloud/api/files/perfiles/<algun_archivo_existente>
# Esperado: HTTP/2 200, Content-Type, Content-Disposition. NO debe verse
# el header X-Accel-Redirect (nginx lo consume y NO lo reenvía al cliente).

# (c) Confirmar en los logs que nginx está sirviendo desde /srv/biblioteca-uploads
sudo tail -f /var/log/nginx/biblioteca-api.access.log
# Pedir un archivo desde otra terminal y ver el log
```

### 8. Limpiar el volumen Docker viejo (solo después de validar)

⚠️ **NO** hagas esto el mismo día. Esperá 24-48hs después de validar que todo
funciona, así si aparece un bug podés volver al volumen rápido.

```bash
docker compose down
docker volume rm biblioteca-api_uploads
docker compose up -d
```

El backup en `/srv/biblioteca-uploads-backup-<timestamp>/` se puede borrar
manualmente cuando estés tranquilo (`sudo rm -rf /srv/biblioteca-uploads-backup-*`).

## Rollback (si algo falla)

```bash
# 1. Revertir docker-compose.yml al volumen managed
git checkout HEAD~1 docker-compose.yml   # ajustar al commit anterior

# 2. Restaurar archivos del backup al volumen Docker
docker compose stop api
docker run --rm \
  -v /srv/biblioteca-uploads-backup-<TS>:/from:ro \
  -v biblioteca-api_uploads:/to \
  alpine sh -c 'cp -a /from/. /to/'

# 3. Revertir nginx
sudo cp /etc/nginx/sites-available/biblioteca-api.bak \
        /etc/nginx/sites-available/biblioteca-api
sudo nginx -t && sudo systemctl reload nginx

# 4. Restart
docker compose up -d --build
```

## Troubleshooting

### `403 Forbidden` al pedir un archivo

nginx no puede leer el archivo en `/srv/biblioteca-uploads/...`. Causas:

```bash
# Verificar permisos
ls -la /srv/biblioteca-uploads/

# Cada archivo debe ser legible por "others" (último 'r' en rwx_r_x_r__).
# Si no, agregar:
sudo chmod -R o+r /srv/biblioteca-uploads/
sudo find /srv/biblioteca-uploads -type d -exec chmod o+x {} \;
```

### `404 Not Found` desde nginx pero el archivo existe

El path del X-Accel-Redirect no coincide con el `alias` de la location. Verificar:

```bash
# Ver qué path está mandando Spring
docker compose logs api | grep -i "X-Accel"

# Cuál es el alias de nginx
sudo grep -A2 "_protected" /etc/nginx/sites-available/biblioteca-api
```

Ambos paths deben coincidir con donde están los archivos en el filesystem.

### Spring no arranca después del rebuild

```bash
docker compose logs api --tail 100
```

Si el error es de permisos al crear `/app/uploads/...`, el UID 1000:1000 no
puede escribir en `/srv/biblioteca-uploads`. Re-ejecutar el chown del paso 1.
