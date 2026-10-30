# Sistema Integral de Gestión Judicial (SIJ Morelos) — Core Module

Plataforma web gubernamental de arquitectura modular distribuida para la gestión jurisdiccional de primera y segunda instancia en el Estado de Morelos. Este repositorio contiene el núcleo transversal (**Core**) sobre el cual operan los módulos de **Oficialía de Partes**, **Gestión de Audiencias**, **Coordinación de Peritos** y **Gestión de Tocas**.

## Especificaciones Técnicas y Stack Tecnológico

| Componente | Tecnología / Estándar | Versión |
| :--- | :--- | :--- |
| **Lenguaje y Runtime** | Java SE Development Kit (LTS) | `21` |
| **Especificación Web** | Jakarta EE (Servlet API) | `6.0.0` (Jakarta EE 10) |
| **Contenedor de Aplicaciones** | Apache Tomcat | `10.1.x` |
| **Motor de Base de Datos** | MySQL Server (JDBC Connector/J) | `8.3.0` |
| **Seguridad y Criptografía** | jBCrypt (Hashing con Salt factor 12) | `0.4` |
| **Intercambio de Datos** | Google Gson (Serialización HTTP/JSON) | `2.10.1` |
| **Notificaciones** | Jakarta Mail API | `2.1.2` |
| **Construcción y Empaquetado** | Apache Maven (`war`) | `3.9+` / Maven Wrapper |

## Arquitectura del Proyecto

El sistema implementa una arquitectura en capas (**N-Tier MVC + DAO**) con separación estricta entre los componentes inmutables del núcleo y el espacio de trabajo de cada módulo jurisdiccional:

```text
src/main/
├── java/mx/edu/utez/sij/
│   ├── core/                  # Núcleo transversal inmutable (Auth, Config, Filters, Utils)
│   ├── controller/
│   │   ├── admin/             # Gobierno jerárquico de identidades y personal judicial
│   │   └── api/               # Endpoints HTTP/JSON propios del módulo
│   ├── service/               # Reglas de negocio y validaciones del módulo
│   ├── dao/                   # Acceso a datos con PreparedStatement y try-with-resources
│   └── model/                 # Entidades POJO y objetos de transferencia
├── resources/
│   ├── sql/schema.sql         # Esquema relacional base auto-ejecutable
│   └── db.properties.example  # Plantilla de configuración local y variables de entorno
└── webapp/
    ├── assets/
    │   ├── css/               # Sistema de diseño en 3 capas (variables, layout, modulo)
    │   ├── js/                # Lógica asíncrona cliente (Fetch API)
    │   └── img/               # Recursos gráficos institucionales
    ├── views/
    │   ├── admin/             # Consola de administración de personal por nivel jerárquico
    │   └── dashboard/         # Bandeja operativa base del módulo (con soporte Skeleton UI)
    └── index.html             # Portal de acceso institucional