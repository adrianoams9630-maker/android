# Conferência MDF-e — Oeste Cargas (Android)

App Android do painel de conferência de notas por manifesto (WF36).

**Baixar:** https://github.com/adrianoams9630-maker/android/releases/download/conferencia-latest/Conferencia-MDFe.apk

- Abre `https://n8n.paideguasistema.com.br/webhook/oeste-cargas-conf-manifesto`
  (troca em *Configurar servidor*, na tela de login).
- Botão 📷 usa o leitor de código de barras do Google (rápido, leitura contínua).
  Sem Google Play Services, cai para a câmera pelo navegador.
- Leitor USB/Bluetooth funciona direto (como teclado).
- Escolher XML do manifesto (vários de uma vez), CSV salvo em Downloads e compartilhável,
  relatório A4 pelo serviço de impressão do Android, tela sempre acesa.

## Build
Cada push no branch `conferencia-app` compila no GitHub Actions e publica a release
`conferencia-latest` (link acima não muda).

Assinatura fixa (para as atualizações instalarem por cima): crie os secrets
`CONF_KEYSTORE_BASE64` (keystore em base64, alias `conferencia`) e `CONF_KEYSTORE_PASSWORD`.
Sem eles o APK sai com chave de debug.

Sistema desenvolvido por **Paidegua Sistema**.
