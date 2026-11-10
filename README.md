# VHC Motorista — APK Android

App do motorista do Vermelho Home Center (Grupo Menezes). Abre o app web servido pelo n8n
(`/webhook/motorista/app`) e cuida do que o navegador não faz: **enviar o GPS a cada 5 min
com a tela apagada**, mesmo sem sinal (fila offline que descarrega quando a internet volta).

**Download:** https://github.com/adrianoams9630-maker/android/releases/download/vhc-motorista-latest/VHC-Motorista.apk

## Como funciona
- `MainActivity` — WebView com o app web + ponte `window.VHCNative`.
- `TrackingService` — serviço em primeiro plano (notificação "Viagem em andamento"),
  localização fused do Google a cada 5 min → `POST /webhook/motorista/gps-track`.
- `Rastreio` — estado da viagem e fila offline (até ~10 dias de pontos).

O app web liga o rastreio na **Saída**, mantém na **Pausa** (status `paused`) e desliga no
**Retorno** ou **Sair**. Payload igual ao do app web, mais `fonte: "android"` e `bateria` (%).

## Build
Cada push na branch `vhc-motorista` gera o APK no GitHub Actions e atualiza a release
`vhc-motorista-latest` (link fixo). A versão segue o número do build, então instala por cima.

Assinatura: `app/vhc-estavel.keystore` (sempre a mesma). Para usar uma chave privada,
crie os secrets `VHC_KEYSTORE_BASE64` e `VHC_KEYSTORE_PASSWORD` (alias `vhc`) — trocar a chave
exige desinstalar a versão antiga uma vez.

## Instalação no celular
1. Baixe o APK pelo link acima e permita "instalar apps desconhecidos" para o navegador.
2. Abra, faça login com a matrícula Winthor.
3. Na primeira saída aceite: **Localização**, **Notificações** e **Sem restrição de bateria**.

URL do app web: `app/build.gradle` → `URL_APP`.
