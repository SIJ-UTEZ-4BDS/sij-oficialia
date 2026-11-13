
document.addEventListener("DOMContentLoaded", function () {
    const inputNombre = document.getElementById("input-nombre-parte");
    const inputRFC = document.getElementById("input-rfc-parte");
    const selectMateria = document.getElementById("select-materia");
    const selectTipoPersona = document.getElementById("select-tipo-persona");
    const panel = document.getElementById("panel-coincidencias-tiempo-real");
    const formulario = document.getElementById("form-agregar-parte");
    const tablaBody = document.getElementById("tabla-partes-body");

    const partes = [];
    let temporizador;
    let numeroConsulta = 0;
    let sesionCargada = false;
    let juzgadoActual = "";
    let idJuzgadoActual = null;

    const contexto = new URL("../../", window.location.href);
    const api = ruta => new URL(ruta, contexto).toString();

    cargarSesion();

    inputNombre?.addEventListener("input", programarBusqueda);
    inputRFC?.addEventListener("input", () => {
        inputRFC.value = inputRFC.value.toUpperCase().trim();
        programarBusqueda();
    });
    selectMateria?.addEventListener("change", programarBusqueda);
    selectTipoPersona?.addEventListener("change", programarBusqueda);

    function programarBusqueda() {
        clearTimeout(temporizador);
        ++numeroConsulta;
        temporizador = setTimeout(buscarDuplicidad, 400);
    }

    async function cargarSesion() {
        const badge = document.getElementById("user-badge");
        const textoJuzgado = document.getElementById("user-juzgado");

        try {
            const respuesta = await fetch(api("api/auth/session"), {
                credentials: "same-origin"
            });

            if (respuesta.status === 401) {
                window.location.href = api("index.html");
                return;
            }

            if (!respuesta.ok) {
                throw new Error("No fue posible verificar la sesión");
            }

            const sesion = await respuesta.json();

            if (badge) {
                badge.textContent =
                    `${sesion.nombreCompleto || "Usuario"} • ${sesion.claveEmpleado || ""}`;
            }

            if (textoJuzgado) {
                textoJuzgado.textContent =
                    `| ${sesion.nombreJuzgado || "Sin juzgado asignado"}`;
            }

            if (sesion.rol === "SUPER_ADMIN" ||
                sesion.rol?.startsWith("ADMIN_")) {
                document.getElementById("link-volver-admin")
                    ?.classList.remove("d-none");
            }

            idJuzgadoActual = sesion.idJuzgado ?? null;

            // Compatibilidad temporal con el servlet existente,
            // que todavía utiliza el parámetro fiscaliaActual.
            juzgadoActual = idJuzgadoActual != null
                ? String(sesion.nombreJuzgado || "").trim()
                : "";

            sesionCargada = true;

            if (inputNombre?.value.trim().length >= 2 ||
                inputRFC?.value.trim().length >= 3) {
                programarBusqueda();
            }

        } catch (error) {
            console.error("Error de sesión:", error);
            mostrarAviso("danger", error.message);
        }
    }

    async function buscarDuplicidad() {
        const nombre = inputNombre?.value.trim() || "";
        const rfc = inputRFC?.value.trim().toUpperCase() || "";
        const materia =
            selectMateria?.selectedOptions[0]?.textContent.trim() || "";

        const consulta = ++numeroConsulta;

        if (nombre.length < 2 && rfc.length < 3) {
            mostrarAviso(
                "light",
                "Escriba al menos dos caracteres del nombre o tres del RFC para iniciar la consulta."
            );
            return;
        }

        if (!sesionCargada) {
            mostrarAviso("light", "Verificando sesión...");
            return;
        }

        if (idJuzgadoActual == null || !juzgadoActual) {
            mostrarAviso(
                "warning",
                "Su usuario no tiene un juzgado asignado. La búsqueda entre juzgados requiere una sesión vinculada a un juzgado."
            );
            return;
        }

        mostrarAviso("light", "Consultando posibles coincidencias...");

        try {
            const datos = new URLSearchParams({
                nombre,
                curpRfc: rfc,
                materia,
                fiscaliaActual: juzgadoActual
            });

            const respuesta = await fetch(
                api("api/oficialia/duplicidad"),
                {
                    method: "POST",
                    credentials: "same-origin",
                    headers: {
                        "Content-Type":
                            "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: datos.toString()
                }
            );

            const resultado = await respuesta.json();

            if (!respuesta.ok) {
                throw new Error(
                    resultado.error || `Error HTTP ${respuesta.status}`
                );
            }

            if (consulta !== numeroConsulta) return;

            renderizarCoincidencias(resultado.coincidencias || []);

        } catch (error) {
            if (consulta !== numeroConsulta) return;

            console.error("Error al consultar:", error);

            mostrarAviso(
                "danger",
                "No fue posible consultar coincidencias: " + error.message
            );
        }
    }

    function mostrarAviso(tipo, mensaje) {
        if (!panel) return;

        const aviso = document.createElement("div");
        aviso.className = `alert alert-${tipo} small mb-0`;
        aviso.setAttribute("role", "status");
        aviso.textContent = mensaje;

        panel.replaceChildren(aviso);
    }

    function renderizarCoincidencias(coincidencias) {
        if (!panel) return;

        if (!Array.isArray(coincidencias) ||
            coincidencias.length === 0) {
            mostrarAviso(
                "success",
                "La consulta terminó sin encontrar coincidencias con los criterios proporcionados."
            );
            return;
        }

        const contenedor = document.createElement("div");

        const aviso = document.createElement("div");
        aviso.className = "alert alert-warning small";
        aviso.textContent =
            `Se encontraron ${coincidencias.length} posible(s) coincidencia(s). ` +
            "Revise los datos antes de continuar. Una coincidencia no confirma por sí sola un asunto duplicado.";

        contenedor.appendChild(aviso);

        const responsive = document.createElement("div");
        responsive.className = "table-responsive";

        const tabla = document.createElement("table");
        tabla.className = "table table-sm table-hover align-middle";

        const encabezado = document.createElement("thead");
        const filaEncabezado = document.createElement("tr");

        ["Folio", "Fiscalía / Juzgado", "Materia", "Estatus"]
            .forEach(titulo => {
                const th = document.createElement("th");
                th.textContent = titulo;
                filaEncabezado.appendChild(th);
            });

        encabezado.appendChild(filaEncabezado);
        tabla.appendChild(encabezado);

        const cuerpo = document.createElement("tbody");

        coincidencias.forEach(item => {
            const fila = document.createElement("tr");

            [
                item.folio,
                item.fiscalia,
                item.materia,
                item.estatus
            ].forEach(valor => {
                const td = document.createElement("td");
                td.textContent = valor == null || valor === ""
                    ? "N/A"
                    : String(valor);
                fila.appendChild(td);
            });

            cuerpo.appendChild(fila);
        });

        tabla.appendChild(cuerpo);
        responsive.appendChild(tabla);
        contenedor.appendChild(responsive);

        panel.replaceChildren(contenedor);
    }

    formulario?.addEventListener("submit", function (event) {
        event.preventDefault();

        if (!formulario.reportValidity()) return;

        const nombre = inputNombre?.value.trim() || "";
        const rfc = inputRFC?.value.trim().toUpperCase() || "";
        const materia = selectMateria?.value || "";
        const tipoPersona = selectTipoPersona?.value || "FISICA";

        const demandante =
            document.getElementById("check-demandante")?.checked;

        const demandado =
            document.getElementById("check-demandado")?.checked;

        if (!demandante && !demandado) {
            alert("Seleccione Demandante, Demandado o ambos.");
            return;
        }

        const rol = demandante && demandado
            ? "AMBOS"
            : demandante
                ? "DEMANDANTE"
                : "DEMANDADO";

        const yaExiste = partes.some(parte =>
            parte.rfc === rfc &&
            parte.materia === materia &&
            parte.rolParte === rol
        );

        if (yaExiste) {
            alert("Esta parte ya fue agregada con el mismo RFC, materia y carácter.");
            return;
        }

        partes.push({
            nombreCompleto: nombre,
            rfc,
            materia,
            tipoPersona,
            rolParte: rol
        });

        renderizarTabla();

        formulario.reset();

        if (selectMateria) {
            selectMateria.value = materia;
        }

        clearTimeout(temporizador);
        ++numeroConsulta;

        mostrarAviso(
            "light",
            "Parte agregada a la lista. Capture otra persona para consultar coincidencias."
        );
    });

    function renderizarTabla() {
        if (!tablaBody) return;

        tablaBody.replaceChildren();

        if (partes.length === 0) {
            const fila = document.createElement("tr");
            const celda = document.createElement("td");

            celda.colSpan = 4;
            celda.className = "text-center py-3";
            celda.textContent = "No hay partes agregadas aún.";

            fila.appendChild(celda);
            tablaBody.appendChild(fila);
            return;
        }

        partes.forEach((parte, indice) => {
            const fila = document.createElement("tr");

            [
                String(indice + 1),
                parte.nombreCompleto,
                parte.rolParte
            ].forEach(valor => {
                const td = document.createElement("td");
                td.textContent = valor;
                fila.appendChild(td);
            });

            const acciones = document.createElement("td");
            acciones.className = "text-center";

            const boton = document.createElement("button");
            boton.type = "button";
            boton.className = "btn-icon-action";
            boton.title = "Eliminar parte";
            boton.setAttribute("aria-label", "Eliminar parte");

            const icono = document.createElement("i");
            icono.className = "bi bi-trash";
            boton.appendChild(icono);

            boton.addEventListener("click", () => {
                partes.splice(indice, 1);
                renderizarTabla();
            });

            acciones.appendChild(boton);
            fila.appendChild(acciones);
            tablaBody.appendChild(fila);
        });
    }
});
