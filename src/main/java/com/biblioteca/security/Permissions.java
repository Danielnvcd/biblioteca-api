package com.biblioteca.security;

import com.biblioteca.exception.ApiException;

import java.util.Set;

/**
 * Centralized role/permission rules — mirrors the original Flask app's matrix.
 *
 * Roles:
 *   - user          — base, read-only
 *   - admin         — manage operational content (NOT almacenes, NOT users)
 *   - super_admin   — full power
 *   - almacen_admin — upload/delete in /almacenes
 *   - almacen_user  — read /almacenes only
 *
 * Protected usernames (cannot be deleted, password cannot be changed):
 *   - Daniel
 *   - Vanesa Rivera
 */
public final class Permissions {

    public static final String ROLE_USER          = "user";
    public static final String ROLE_ADMIN         = "admin";
    public static final String ROLE_SUPER_ADMIN   = "super_admin";
    public static final String ROLE_ALMACEN_ADMIN = "almacen_admin";
    public static final String ROLE_ALMACEN_USER  = "almacen_user";

    public static final Set<String> PROTECTED_USERNAMES = Set.of("Daniel", "Vanesa Rivera");

    private Permissions() {}

    /* ---------- Predicates ---------- */

    public static boolean isSuperAdmin(UserPrincipal p) {
        return p != null && ROLE_SUPER_ADMIN.equals(p.getRole());
    }

    public static boolean isAdmin(UserPrincipal p) {
        if (p == null || p.getRole() == null) return false;
        return ROLE_ADMIN.equals(p.getRole()) || ROLE_SUPER_ADMIN.equals(p.getRole());
    }

    public static boolean canManageAlmacenes(UserPrincipal p) {
        if (p == null || p.getRole() == null) return false;
        return ROLE_SUPER_ADMIN.equals(p.getRole()) || ROLE_ALMACEN_ADMIN.equals(p.getRole());
    }

    public static boolean canViewAlmacenes(UserPrincipal p) {
        if (p == null || p.getRole() == null) return false;
        return ROLE_SUPER_ADMIN.equals(p.getRole())
                || ROLE_ALMACEN_ADMIN.equals(p.getRole())
                || ROLE_ALMACEN_USER.equals(p.getRole());
    }

    public static boolean canDownloadAlmacenes(UserPrincipal p) {
        if (p == null || p.getRole() == null) return false;
        return canViewAlmacenes(p) || ROLE_ADMIN.equals(p.getRole());
    }

    public static boolean isProtectedUsername(String username) {
        return username != null && PROTECTED_USERNAMES.contains(username);
    }

    /* ---------- Guards (throw 403 if not allowed) ---------- */

    public static void requireSuperAdmin(UserPrincipal p) {
        if (!isSuperAdmin(p)) {
            throw ApiException.forbidden("Se requieren privilegios de super administrador");
        }
    }

    public static void requireAdmin(UserPrincipal p) {
        if (!isAdmin(p)) {
            throw ApiException.forbidden("Se requieren privilegios de administrador");
        }
    }

    public static void requireAlmacenManage(UserPrincipal p) {
        if (!canManageAlmacenes(p)) {
            throw ApiException.forbidden("Solo super_admin o almacen_admin pueden modificar almacenes");
        }
    }

    public static void requireAlmacenView(UserPrincipal p) {
        if (!canViewAlmacenes(p)) {
            throw ApiException.forbidden("Solo personal de almacenes puede acceder a esta sección");
        }
    }

    /* ---------- Per-category content rules ---------- */

    /**
     * Upload to a content category (matches original Flask `handle_upload`):
     *   - lecciones        → any logged-in user
     *   - almacenes        → super_admin or almacen_admin
     *   - everything else  → admin or super_admin
     */
    public static void requireUploadFor(UserPrincipal p, String category) {
        if (p == null) throw ApiException.unauthorized("No autenticado");
        if ("lecciones".equalsIgnoreCase(category)) return;
        if ("almacenes".equalsIgnoreCase(category)) {
            requireAlmacenManage(p);
            return;
        }
        requireAdmin(p);
    }

    /**
     * Delete content (matches original Flask `eliminar_contenido`):
     *   - almacenes        → super_admin or almacen_admin
     *   - everything else  → super_admin only
     */
    public static void requireDeleteFor(UserPrincipal p, String category) {
        if ("almacenes".equalsIgnoreCase(category)) {
            requireAlmacenManage(p);
            return;
        }
        requireSuperAdmin(p);
    }
}
