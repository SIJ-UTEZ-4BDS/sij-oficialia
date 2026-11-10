
document.addEventListener("DOMContentLoaded", function () {
    const inputNombre = document.getElementById("input-nombre-parte");
    const selectMateria = document.getElementById("select-materia");
    const panelCoincidencias = document.getElementById("panel-coincidencias-tiempo-real");
    const formAgregarParte = document.getElementById("form-agregar-parte");
    const tablaPartesBody = document.getElementById("tabla-partes-body");
    const listaPartes = [];
    let debounceTimer;
    let consultaActual = 0;
    let fiscaliaActual = "";

    const contexto = new URL("../../", window.location.href);
    const api = (ruta) => new URL(ruta, contexto).toString();

    cargarSesion();

    inputNombre?.addEventListener("input", () => {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(buscarDuplicidad, 300);
    });

    selectMateria?.addEventListener("change", buscarDuplicidad);

    async function cargarSesion() {
        const badge = document.getElementById("user-badge");
        const juzgado = document.getElementById("user-juzgado");

        try {
            const respuesta = await fetch(api("api/auth/session"), {
                credentials: "same-origin"
            });

            if (!respuesta.ok) {
                window.location.href = api("index.html");
                return;
            }

            const sesion = await respuesta.json();

            if (badge) {
                badge.textContent =
                    `${sesion.nombreCompleto || "Usuario"} • ${sesion.claveEmpleado || ""}`;
            }

            if (juzgado) {
                juzgado.textContent = `| ${sesion.nombreJuzgado || ""}`;
            }

            if (sesion.rol?.startsWith("ADMIN_")) {
                document.getElementById("link-volver-admin")
                    ?.classList.remove("d-none");
            }

            // Ajustar al identificador real de fiscalia de la sesion.
            fiscaliaActual = String(
                sesion.fiscaliaActual ?? sesion.nombreFiscalia ?? ""
            ).trim();

        } catch (error) {
            console.error("Error al verificar sesión:", error);

            if (badge) {
                badge.textContent = "No fue posible verificar la sesión";
            }
        }
    }

    async function buscarDuplicidad() {
        const termino = inputNombre?.value.trim() || "";
        const materia =
            selectMateria?.selectedOptions[0]?.textContent.trim() || "";
        const consulta = ++consultaActual;

        if (termino.length < 2) {
            mostrarAviso(
                "light",
                "Capture al menos dos caracteres del nombre para iniciar la consulta automática."
            );
            return;
        }

        if (!fiscaliaActual) {
            mostrarAviso(
                "warning",
                "La sesión no proporciona la fiscalía actual. Es necesario configurar ese dato para consultar coincidencias."
            );
            return;
        }

        mostrarAviso("light", "Consultando posibles coincidencias...");

        try {
            const datos = new URLSearchParams({
                nombre: termino,
                materia,
                fiscaliaActual
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

            if (consulta === consultaActual) {
                renderizarCoincidencias(resultado.coincidencias || []);
            }

        } catch (error) {
            if (consulta === consultaActual) {
                console.error("Error al consultar duplicidad:", error);

                mostrarAviso(
                    "danger",
                    "No fue posible consultar coincidencias: " + error.message
                );
            }
        }
    }

    function mostrarAviso(tipo, mensaje) {
        if (!panelCoincidencias) return;

        const div = document.createElement("div");
        div.className = `alert alert-${tipo} small mb-0`;
        div.setAttribute("role", "status");
        div.textContent = mensaje;

        panelCoincidencias.replaceChildren(div);
    }

    function renderizarCoincidencias(coincidencias) {
        if (!panelCoincidencias) return;

        if (!Array.isArray(coincidencias) || coincidencias.length === 0) {
            mostrarAviso(
                "success",
                "No se encontraron coincidencias con los criterios consultados."
            );
            return;
        }

        const contenedor = document.createElement("div");

        const titulo = document.createElement("p");
        titulo.className = "text-warning-emphasis fw-semibold small";
        titulo.textContent =
            `Se encontraron ${coincidencias.length} coincidencia(s)`;

        contenedor.appendChild(titulo);

        const responsive = document.createElement("div");
        responsive.className = "table-responsive";

        const tabla = document.createElement("table");
        tabla.className = "table table-sm align-middle";

        const encabezado = document.createElement("thead");
        const filaEncabezado = document.createElement("tr");

        ["Folio", "Fiscalía", "Materia", "Estatus"].forEach(texto => {
            const th = document.createElement("th");
            th.textContent = texto;
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
                const celda = document.createElement("td");

                celda.textContent =
                    valor == null || valor === ""
                        ? "N/A"
                        : String(valor);

                fila.appendChild(celda);
            });

            cuerpo.appendChild(fila);
        });

        tabla.appendChild(cuerpo);
        responsive.appendChild(tabla);
        contenedor.appendChild(responsive);

        panelCoincidencias.replaceChildren(contenedor);
    }

    formAgregarParte?.addEventListener("submit", event => {
        event.preventDefault();

        const nombre = inputNombre?.value.trim() || "";

        const demandante =
            document.getElementById("check-demandante")?.checked;

        const demandado =
            document.getElementById("check-demandado")?.checked;

        if (!nombre) {
            alert("El nombre de la parte es obligatorio.");
            return;
        }

        if (!demandante && !demandado) {
            alert("Selecciona Demandante, Demandado o ambos.");
            return;
        }

        const rol =
            demandante && demandado
                ? "AMBOS"
                : demandante
                    ? "DEMANDANTE"
                    : "DEMANDADO";

        listaPartes.push({
            nombreCompleto: nombre,
            rolParte: rol
        });

        renderizarTablaPartes();

        const materiaAnterior = selectMateria?.value;

        formAgregarParte.reset();

        if (selectMateria) {
            selectMateria.value = materiaAnterior;
        }

        ++consultaActual;

        mostrarAviso(
            "light",
            "Capture al menos dos caracteres del nombre para iniciar la consulta automática."
        );
    });

    function renderizarTablaPartes() {
        if (!tablaPartesBody) return;

        tablaPartesBody.replaceChildren();

        if (listaPartes.length === 0) {
            const fila = document.createElement("tr");
            const celda = document.createElement("td");

            celda.colSpan = 4;
            celda.className = "text-center py-3";
            celda.textContent = "No hay partes agregadas aún.";

            fila.appendChild(celda);
            tablaPartesBody.appendChild(fila);
            return;
        }

        listaPartes.forEach((parte, indice) => {
            const fila = document.createElement("tr");

            [
                String(indice + 1),
                parte.nombreCompleto,
                parte.rolParte
            ].forEach(valor => {
                const celda = document.createElement("td");
                celda.textContent = valor;
                fila.appendChild(celda);
            });

            const acciones = document.createElement("td");
            acciones.className = "text-center";

            const boton = document.createElement("button");
            boton.type = "button";
            boton.className = "btn-icon-action";
            boton.title = "Eliminar parte";
            boton.setAttribute("aria-label", "Eliminar parte");
            boton.innerHTML = '<i class="bi bi-trash"></i>';

            boton.addEventListener("click", () => {
                listaPartes.splice(indice, 1);
                renderizarTablaPartes();
            });

            acciones.appendChild(boton);
            fila.appendChild(acciones);
            tablaPartesBody.appendChild(fila);
        });
    }
});
