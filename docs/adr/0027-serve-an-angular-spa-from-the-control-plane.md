---
status: accepted
---

# Serve an Angular SPA from the control plane

Skywright's web frontend is a client-rendered Angular application built as an independently testable static artifact and packaged into the Spring control-plane application as a classpath web resource. Spring serves the shell, static assets, and `/api/v1` from one private origin and one version-paired deployment, avoiding a second production web runtime, cross-origin policy, and frontend/backend release skew while preserving a separate frontend build boundary.

The application uses Angular's standalone, zoneless, signal-based model and official router. Server-side rendering, prerendering, a service worker, a separately deployed static server, and a frontend-owned system of record are excluded: the private operational UI has no public-content or SEO need, browser lifetime owns no control-plane work, and backend observations remain authoritative through the generated OpenAPI client.

## Consequences

The frontend is coupled to Angular and its build model, while Node and pnpm remain build-and-test tools rather than production runtimes. Spring must forward genuine client-side routes to the shell without intercepting product API, OpenAPI, operational, static-asset, or explicit proxy namespaces; content-hashed assets may be cached immutably, while the shell document must revalidate so a newly loaded browser receives the version packaged with the running backend.
