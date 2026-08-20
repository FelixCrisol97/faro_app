# Cómo arrancar el proyecto una vez que Flutter esté instalado

El código en `lib/` y `pubspec.yaml` ya está escrito, pero fue creado a mano
porque el Flutter SDK todavía se estaba instalando. Ninguno de estos pasos
se ha podido correr todavía — sigue esta lista en orden la primera vez.

## 1. Verifica la instalación

```
flutter doctor
```

Resuelve cualquier cosa marcada en rojo (sobre todo lo relacionado a
Windows desktop / Visual Studio, que es el toolchain que compila la app
de escritorio).

## 2. Genera las carpetas nativas de la plataforma

Este `pubspec.yaml` fue escrito a mano y **no** incluye las carpetas
`windows/`, `macos/`, `linux/` que Flutter necesita para compilar un build
de escritorio. Desde la raíz del proyecto (`c:\software_development\faro`):

```
flutter create --platforms=windows .
```

(Agrega `,macos,linux` si en algún momento se va a compilar en esas
plataformas también.) Ese comando puede preguntar si quiere sobreescribir
`pubspec.yaml` — dile que no; si lo sobreescribe igual, vuelve a aplicar
las `dependencies`/`dev_dependencies`/`flutter:` de este repo (están en
git si ya hiciste commit, o pégalas de nuevo desde este archivo).

## 3. Instala las dependencias

```
flutter pub get
```

## 4. Fuentes reales

Ya están en `assets/fonts/` (descargadas del repo oficial de Google Fonts
el 2026-07-17): `Caprasimo-Regular.ttf` y `Figtree-Variable.ttf`. Figtree
solo se distribuye como un único archivo de peso variable — `pubspec.yaml`
lo registra 4 veces a distintos `weight:` nominales para que la familia
resuelva bien en `TextTheme` aunque el motor no anime el eje variable en
runtime.

## 5. Corre la app

```
flutter run -d windows
```

## 6. Corre los tests

```
flutter test
```

Ya hay dos archivos de test (`test/sql_statement_resolver_test.dart`,
`test/sql_guard_test.dart`) que cubren la lógica pura de "qué sentencia se
ejecuta" y la validación de solo-lectura.

## Errores esperables la primera vez que compile

Este código nunca se ha compilado (no había SDK disponible durante esta
sesión). Es razonable esperar 1-2 rondas de errores de tipos/imports
menores al primer `flutter run` — avísame en la siguiente sesión y los
resolvemos juntos.
