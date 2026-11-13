"use strict";

document.addEventListener("DOMContentLoaded", () => {
    const form = document.getElementById("form-parte1");
    const nombre = document.getElementById("input-nombre-parte");
    const curpRfc = document.getElementById("input-rfc-parte");
    const materia = document.getElementById("select-materia");
    const tipoPersona = document.getElementById("select-tipo-persona");
    const panel = document.getElementById("panel-coincidencias-tiempo-real");
    const tabla = document.getElementById("tabla-coincidencias");
    const btnSiguiente = document.getElementById("btn-siguiente");
    const contexto = new URL("../../", window.location.href);
    const api = ruta => new URL(ruta, contexto).toString();

    let idJuzgado = null;
    let timer = null;
    let requestId = 0;
    let consultaActual = "";
    let verificacionCompleta = false;
    let duplicado = false;

    inicializar().catch(error => mostrarError(error.message));

    async function inicializar() {
        const [sesionResponse, materiasResponse] = await Promise.all([
            fetch(api("api/auth/session"), { credentials: "same-origin" }),
            fetch(api("api/oficialia/mesas-oficio?accion=materias"), {
                credentials: "same-origin",
                headers: { Accept: "application/json" }
            })
        ]);

        if (sesionResponse.status === 401) {
            window.location.href = api("index.html");
            return;
        }
        if (!sesionResponse.ok) {
            throw new Error("No fue posible verificar la sesión.");
        }
        if (!materiasResponse.ok) {
            throw new Error("No fue posible cargar las materias.");
        }

        const sesion = await sesionResponse.json();
        const datosMaterias = await materiasResponse.json();
        if (!sesion.ok || !datosMaterias.ok) {
            throw new Error(datosMaterias.mensaje || "No fue posible inicializar el registro.");
        }
        if (sesion.rol !== "SUPER_ADMIN" &&
            !String(sesion.rol || "").endsWith("_OFICIALIA")) {
            window.location.href = api("index.html");
            return;
        }

        idJuzgado = Number(sesion.idJuzgado);
        document.getElementById("user-badge").textContent =
            `${sesion.nombreCompleto || "Usuario"} • ${sesion.claveEmpleado || ""}`;
        document.getElementById("user-juzgado").textContent =
            `| ${sesion.nombreJuzgado || "Sin juzgado asignado"}`;

        if (sesion.rol === "ADMIN_OFICIALIA" || sesion.rol === "SUPER_ADMIN") {
            document.getElementById("link-consultar").classList.remove("d-none");
        }
        if (sesion.rol.startsWith("ADMIN_") || sesion.rol === "SUPER_ADMIN") {
            document.getElementById("link-volver-admin").classList.remove("d-none");
        }

        const opciones = datosMaterias.materias || [];
        materia.replaceChildren(new Option("Selecciona una materia", ""));
        opciones.forEach(item => {
            materia.add(new Option(item.nombreMateria, item.idMateria));
        });
        if (opciones.length === 0) {
            throw new Error("No hay materias activas para registrar.");
        }

        form.addEventListener("input", programarVerificacion);
        form.addEventListener("change", programarVerificacion);
        curpRfc.addEventListener("input", () => {
            curpRfc.value = curpRfc.value.toUpperCase().trim();
        });
        btnSiguiente.addEventListener("click", continuar);

        const anterior = sessionStorage.getItem("oficialia_parte1_validada");
        if (anterior) {
            try {
                const datos = JSON.parse(anterior);
                nombre.value = datos.promovente?.nombreCompleto || "";
                curpRfc.value = datos.promovente?.curpRfc || "";
                tipoPersona.value = datos.promovente?.tipoPersona || "FISICA";
                materia.value = datos.idMateria || "";
                if (nombre.value && materia.value) {
                    programarVerificacion();
                }
            } catch (error) {
                console.error("No se pudieron recuperar los datos de Parte 1.", error);
                sessionStorage.removeItem("oficialia_parte1_validada");
            }
        }
    }

    function programarVerificacion() {
        clearTimeout(timer);
        requestId++;
        verificacionCompleta = false;
        duplicado = false;
        btnSiguiente.disabled = true;

        const valorNombre = nombre.value.trim();
        if (valorNombre.length < 2 || !materia.value) {
            mostrarAviso("Capture al menos dos caracteres del nombre y seleccione la materia para verificar.");
            renderizarCoincidencias([]);
            return;
        }

        mostrarAviso("Verificando coincidencias...");
        timer = setTimeout(buscarCoincidencias, 350);
    }

    async function buscarCoincidencias() {
        const consulta = ++requestId;
        const datos = {
            nombre: nombre.value.trim(),
            curpRfc: curpRfc.value.trim().toUpperCase(),
            idMateria: materia.value
        };
        consultaActual = JSON.stringify(datos);

        if (!Number.isInteger(idJuzgado) || idJuzgado <= 0) {
            mostrarError("Su cuenta no tiene un juzgado asignado.");
            return;
        }

        try {
            const body = new URLSearchParams(datos);
            const response = await fetch(api("api/oficialia/duplicidad"), {
                method: "POST",
                credentials: "same-origin",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                    Accept: "application/json"
                },
                body: body.toString()
            });
            const result = await response.json();

            if (!response.ok || !result.ok) {
                throw new Error(result.mensaje || result.error || `Error HTTP ${response.status}`);
            }
            if (consulta !== requestId) return;

            const currentValues = JSON.stringify({
                nombre: nombre.value.trim(),
                curpRfc: curpRfc.value.trim().toUpperCase(),
                idMateria: materia.value
            });
            if (currentValues !== consultaActual) return;

            const matches = Array.isArray(result.coincidencias)
                ? result.coincidencias
                : [];
            duplicado = matches.length > 0;
            verificacionCompleta = true;
            renderizarCoincidencias(matches);

            if (duplicado) {
                mostrarAviso("Se encontraron posibles coincidencias en otra fiscalía. No es posible continuar con un registro duplicado.");
            } else {
                mostrarAviso("Sin coincidencias en otras fiscalías. Puede continuar.");
            }
            btnSiguiente.disabled = duplicado || !form.reportValidity();
        } catch (error) {
            if (consulta !== requestId) return;
            verificacionCompleta = false;
            btnSiguiente.disabled = true;
            mostrarError(`No fue posible verificar coincidencias: ${error.message}`);
        }
    }

    function renderizarCoincidencias(coincidencias) {
        tabla.replaceChildren();
        if (coincidencias.length === 0) {
            const fila = document.createElement("tr");
            const celda = document.createElement("td");
            celda.colSpan = 4;
            celda.className = "text-center text-muted";
            celda.textContent = "Sin coincidencias.";
            fila.appendChild(celda);
            tabla.appendChild(fila);
            return;
        }

        coincidencias.forEach(coincidencia => {
            const fila = document.createElement("tr");
            [coincidencia.juzgado, coincidencia.distrito,
                coincidencia.materia, coincidencia.fecha].forEach(valor => {
                const celda = document.createElement("td");
                celda.textContent = valor == null ? "" : String(valor);
                fila.appendChild(celda);
            });
            tabla.appendChild(fila);
        });
    }

    function continuar() {
        if (!verificacionCompleta || duplicado || !form.reportValidity()) {
            return;
        }

        sessionStorage.setItem("oficialia_parte1_validada", JSON.stringify({
            idMateria: materia.value,
            materia: materia.selectedOptions[0].textContent,
            promovente: {
                nombreCompleto: nombre.value.trim(),
                curpRfc: curpRfc.value.trim().toUpperCase(),
                tipoPersona: tipoPersona.value,
                rolParte: "DEMANDANTE"
            }
        }));
        window.location.href = "registro-parte2.html";
    }

    function mostrarAviso(mensaje) {
        const alerta = document.createElement("div");
        alerta.className = "alert alert-info border mb-0";
        alerta.setAttribute("role", "status");
        alerta.textContent = mensaje;
        panel.replaceChildren(alerta);
    }

    function mostrarError(mensaje) {
        const alerta = document.createElement("div");
        alerta.className = "alert alert-danger mb-0";
        alerta.setAttribute("role", "alert");
        alerta.textContent = mensaje;
        panel.replaceChildren(alerta);
        btnSiguiente.disabled = true;
    }
});
