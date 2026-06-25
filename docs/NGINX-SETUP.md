# Configs de nginx (espejo del VPS)

La carpeta `nginx/` (en la raíz del repo) es un **espejo exacto** de la estructura
de `/etc/nginx/` en el VPS. Lo que está ahí se pega tal cual en su path
equivalente del server.

## Estructura

```
nginx/
├── nginx.conf                       →  /etc/nginx/nginx.conf
└── sites-available/
    └── biblioteca-api               →  /etc/nginx/sites-available/biblioteca-api
                                        (symlink desde sites-enabled/)
```

## Cómo se relacionan

```
nginx.conf (global)
└── http { ... }
        └── include sites-enabled/*  ←  acá adentro entra biblioteca-api
              └── server { ... config específica de la API ... }
```

**Las dos configs se usan juntas, no son alternativas.** El `nginx.conf` global
define lo compartido (gzip, buffers, real_ip, headers); el `biblioteca-api`
define el server{} de la API (listen, proxy_pass, etc.).

## Aplicar al VPS

```bash
# Desde la raíz del repo (donde está nginx/):

# 1. Copiar el global
sudo cp nginx/nginx.conf /etc/nginx/nginx.conf

# 2. Copiar el site
sudo cp nginx/sites-available/biblioteca-api /etc/nginx/sites-available/biblioteca-api

# 3. Asegurar que el site está enabled (debería estar ya)
ls -la /etc/nginx/sites-enabled/biblioteca-api
# si no existe el symlink:
sudo ln -s /etc/nginx/sites-available/biblioteca-api \
           /etc/nginx/sites-enabled/biblioteca-api

# 4. Validar y RESTART (no reload — comprobamos que reload no aplica algunos cambios)
sudo nginx -t
sudo systemctl restart nginx
```

> ⚠️ **Usar `restart` en lugar de `reload`** — en algunos cambios (especialmente
> al agregar/quitar locations), `systemctl reload nginx` no aplica el cambio
> aunque `nginx -t` y `nginx -T` digan que sí está cargado. `restart` lo aplica.

## Verificación

```bash
# Desde el VPS — nginx solo en localhost:
sudo ss -tlnp | grep nginx
# Esperado: 127.0.0.1:80   (NO 0.0.0.0:80 ni *:80)

curl -I http://127.0.0.1/nginx-health
# Esperado: HTTP/1.1 200

# Desde tu PC — API responde por Cloudflare Tunnel:
curl.exe -I https://api.bibliotecamaxipet.cloud/actuator/health
# Esperado: HTTP/2 200 con  server: cloudflare
```
