# SSW — App Android

Abre o sistema SSW em tela própria (WebView), sem navegador.

## Recursos
- Login mantido entre aberturas (cookies persistentes)
- Janelas pop-up do SSW abrem empilhadas dentro do app (Voltar fecha a janela)
- Downloads (PDF, XML, DACTE, relatórios) vão para **Downloads** do celular
- Upload de arquivos, zoom com pinça, impressão / salvar PDF
- Menu: Início · Recarregar · Fechar janela · Imprimir · Modo desktop · Alterar endereço · Sair (limpa login)
- Links `https://*.ssw.inf.br` podem abrir direto no app

Endereço padrão: `https://sistema.ssw.inf.br/bin/ssw0422`
(trocar em `app/build.gradle` → `URL_PADRAO`, ou no app em Menu > Alterar endereço).

## Gerar o APK

**Opção A — GitHub (sem instalar nada)**
1. Crie um repositório e envie esta pasta.
2. Aba **Actions** → *Gerar APK* → roda sozinho a cada push (ou *Run workflow*).
3. Baixe o artefato **SSW-apk** e instale no celular (permitir "fontes desconhecidas").

**Opção B — Android Studio**
1. File > Open → pasta `ssw-app`.
2. Build > Build App Bundle(s) / APK(s) > Build APK(s).
3. APK em `app/build/outputs/apk/`.

Ou via terminal: `./gradlew assembleRelease`

Requisitos: Android 8.0+ (API 26). O release é assinado com a chave de debug — para Play Store, configure uma keystore própria.

---
Paidegua Sistema
