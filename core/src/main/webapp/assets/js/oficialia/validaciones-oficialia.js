"use strict";

(function (global) {
    const estadosCurp = new Set([
        "AS", "BC", "BS", "CC", "CL", "CM", "CS", "CH", "DF", "DG",
        "GT", "GR", "HG", "JC", "MC", "MN", "MS", "NT", "NL", "OC",
        "PL", "QT", "QR", "SP", "SL", "SR", "TC", "TS", "TL", "VZ",
        "YN", "ZS", "NE"
    ]);

    function fechaValida(valor, offset) {
        const anio = Number(valor.slice(offset, offset + 2));
        const mes = Number(valor.slice(offset + 2, offset + 4));
        const dia = Number(valor.slice(offset + 4, offset + 6));
        if (mes < 1 || mes > 12 || dia < 1) return false;
        const diasMes = [
            31, anio % 4 === 0 ? 29 : 28, 31, 30, 31, 30,
            31, 31, 30, 31, 30, 31
        ];
        return dia <= diasMes[mes - 1];
    }

    function esCurp(valor) {
        const curp = String(valor || "").trim().toUpperCase();
        const coincidencia = curp.match(
            /^([A-Z][AEIOUX][A-Z]{2})(\d{6})([HM])([A-Z]{2})([B-DF-HJ-NP-TV-Z]{3})([A-Z0-9])(\d)$/
        );
        if (!coincidencia || !estadosCurp.has(coincidencia[4]) ||
            !fechaValida(curp, 4)) {
            return false;
        }

        const valores = "0123456789ABCDEFGHIJKLMNÑOPQRSTUVWXYZ";
        let suma = 0;
        for (let indice = 0; indice < 17; indice++) {
            const valorCaracter = valores.indexOf(curp[indice]);
            if (valorCaracter < 0) return false;
            suma += valorCaracter * (18 - indice);
        }
        return (10 - suma % 10) % 10 === Number(curp[17]);
    }

    function esRfc(valor) {
        const rfc = String(valor || "").trim().toUpperCase();
        if (rfc === "XAXX010101000" || rfc === "XEXX010101000") {
            return true;
        }
        if (!/^[A-ZÑ&]{3,4}\d{6}[A-Z0-9]{2}[0-9A]$/.test(rfc)) {
            return false;
        }

        const fechaOffset = rfc.length === 12 ? 3 : 4;
        if (!fechaValida(rfc, fechaOffset)) return false;

        const alfabeto = "0123456789ABCDEFGHIJKLMN&OPQRSTUVWXYZ Ñ";
        const normalizado = rfc.length === 12 ? ` ${rfc}` : rfc;
        let suma = 0;
        for (let indice = 0; indice < 12; indice++) {
            const valorCaracter = alfabeto.indexOf(normalizado[indice]);
            if (valorCaracter < 0) return false;
            suma += valorCaracter * (13 - indice);
        }
        const residuo = suma % 11;
        const verificador = residuo === 0
            ? "0" : residuo === 1 ? "A" : String(11 - residuo);
        return verificador === rfc[rfc.length - 1];
    }

    function validarIdentificador(valor, tipoPersona) {
        const normalizado = String(valor || "").trim().toUpperCase();
        const tipo = String(tipoPersona || "").trim().toUpperCase();
        if (tipo && tipo !== "FISICA" && tipo !== "MORAL") {
            return {
                valido: false,
                normalizado,
                mensaje: "Selecciona un tipo de persona válido."
            };
        }
        if (!normalizado) return { valido: true, normalizado };
        if (esCurp(normalizado) && tipo !== "MORAL") {
            return { valido: true, normalizado, tipo: "CURP" };
        }
        if (esRfc(normalizado)) {
            if (tipo === "FISICA" && normalizado.length !== 13) {
                return {
                    valido: false,
                    normalizado,
                    mensaje: "El RFC de una persona física debe tener 13 caracteres."
                };
            }
            if (tipo === "MORAL" && normalizado.length !== 12) {
                return {
                    valido: false,
                    normalizado,
                    mensaje: "El RFC de una persona moral debe tener 12 caracteres."
                };
            }
            return { valido: true, normalizado, tipo: "RFC" };
        }
        return {
            valido: false,
            normalizado,
            mensaje: tipo === "MORAL"
                ? "Ingresa un RFC válido de 12 caracteres para persona moral."
                : tipo === "FISICA"
                    ? "Ingresa un RFC válido de 13 caracteres o una CURP válida para persona física."
                    : "Ingresa un RFC (12 o 13 caracteres) o CURP (18) válido."
        };
    }

    function normalizarTelefono(valor) {
        const telefono = String(valor || "").trim();
        if (!/^[+\d\s().-]+$/.test(telefono)) return "";
        let digitos = telefono.replace(/\D/g, "");
        if (digitos.length === 12 && digitos.startsWith("52")) {
            digitos = digitos.slice(2);
        }
        return /^\d{10}$/.test(digitos) ? digitos : "";
    }

    function limpiarTelefonoCaptura(valor) {
        return String(valor || "").replace(/\D/g, "").slice(0, 10);
    }

    function textoDireccionValido(valor) {
        const texto = String(valor || "").trim();
        return texto.length >= 2 &&
            /\p{L}/u.test(texto) &&
            /^[\p{L}\p{M}0-9 .,'/#()-]+$/u.test(texto);
    }

    function numeroDomicilioValido(valor) {
        return /^[A-Z0-9#][A-Z0-9#./ -]{0,19}$/i.test(
            String(valor || "").trim());
    }

    function esCorreoValido(valor) {
        const correo = String(valor || "").trim();
        if (correo.length > 254) return false;
        const partes = correo.split("@");
        if (partes.length !== 2 || partes[0].length > 64 ||
            !/^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+$/i.test(partes[0]) ||
            partes[0].startsWith(".") || partes[0].endsWith(".") ||
            partes[0].includes("..")) {
            return false;
        }
        const dominios = partes[1].split(".");
        return dominios.length > 1 && dominios[dominios.length - 1].length >= 2 &&
            dominios.every(etiqueta =>
            etiqueta.length > 0 && etiqueta.length <= 63 &&
            /^[A-Z0-9](?:[A-Z0-9-]*[A-Z0-9])?$/i.test(etiqueta));
    }

    function marcarInvalido(elemento, mensaje) {
        elemento.classList.add("is-invalid");
        elemento.classList.remove("is-valid");
        elemento.setAttribute("aria-invalid", "true");
        let feedback = elemento.parentElement.querySelector(".invalid-feedback");
        if (!feedback) {
            feedback = document.createElement("div");
            feedback.className = "invalid-feedback";
            elemento.insertAdjacentElement("afterend", feedback);
        }
        feedback.textContent = mensaje;
    }

    function marcarValido(elemento) {
        elemento.classList.remove("is-invalid");
        elemento.removeAttribute("aria-invalid");
        elemento.classList.toggle("is-valid", Boolean(elemento.value.trim()));
    }

    function limpiarEstado(elemento) {
        elemento.classList.remove("is-invalid", "is-valid");
        elemento.removeAttribute("aria-invalid");
        const feedback = elemento.parentElement.querySelector(".invalid-feedback");
        if (feedback) feedback.textContent = "";
    }

    function formatearDireccion(direccion) {
        const partes = [
            `Calle ${direccion.calle}`,
            `No. exterior ${direccion.numeroExterior}`
        ];
        if (direccion.numeroInterior) {
            partes.push(`Interior ${direccion.numeroInterior}`);
        }
        partes.push(
            `Colonia ${direccion.colonia}`,
            `Municipio ${direccion.municipio}`,
            `Estado ${direccion.estado}`,
            `C.P. ${direccion.codigoPostal}`
        );
        return partes.join(", ");
    }

    async function leerRespuestaJson(respuesta, operacion) {
        const contenido = await respuesta.text();
        try {
            const resultado = JSON.parse(contenido);
            if (!resultado || typeof resultado !== "object" ||
                Array.isArray(resultado)) {
                throw new Error("La respuesta JSON no tiene un objeto válido.");
            }
            return resultado;
        } catch (error) {
            if (error instanceof SyntaxError) {
                const tipo = respuesta.headers.get("content-type") ||
                    "tipo de contenido desconocido";
                throw new Error(
                    `El servidor devolvió una respuesta no JSON al ${operacion} (HTTP ${respuesta.status}; ${tipo}).`);
            }
            throw error;
        }
    }

    global.OficialiaValidation = Object.freeze({
        validarIdentificador,
        normalizarTelefono,
        limpiarTelefonoCaptura,
        textoDireccionValido,
        numeroDomicilioValido,
        esCorreoValido,
        marcarInvalido,
        marcarValido,
        limpiarEstado,
        formatearDireccion,
        leerRespuestaJson
    });
})(globalThis);
