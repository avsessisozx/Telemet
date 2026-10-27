<p align="center"><img src="icon.png" width="100" alt="Telemet"></p>

<h1 align="center">Telemet</h1>

<p align="center"><b>Telemetria GPS gratuita para câmeras DJI Osmo Action. Use seu celular Android no lugar do controle GPS Bluetooth da DJI.</b></p>

<p align="center">[English](../README.md) · [Türkçe](README.tr.md) · [Español](README.es.md) · [Français](README.fr.md) · [Italiano](README.it.md) · [Deutsch](README.de.md) · **Português** · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Русский](README.ru.md) · [العربية](README.ar.md)</p>

---

As câmeras DJI Osmo Action só gravam velocidade, rota e altitude no vídeo quando o **controle GPS Bluetooth** da DJI está conectado. O Telemet transforma seu celular nesse controle: conecta por Bluetooth, envia o GPS do celular 10 vezes por segundo e a câmera grava direto no arquivo de vídeo. Abra o vídeo no **DJI Mimo** ou **DJI Studio** e adicione o painel de velocidade, rota e altitude. Sem conversão de FIT, sem hardware extra. Feito para motociclistas, funciona igual de bike, de carro ou a pé.

## Recursos

- 📍 **Emula o controle GPS da DJI**: os dados vão embutidos no vídeo
- 🎬 **Controle da câmera**: inicie/pare a gravação pelo app ou pela notificação; modo, estado e bateria
- 🗂️ **Cada passeio separado**: "Meus passeios" agrupados por dia com distância, duração, velocidade máx./média e subida
- 💾 **Backup FIT + GPX de cada passeio**: importe o FIT no DJI Studio se a câmera perdeu dados
- 🏍️ **Ângulo de inclinação e força G** pelo giroscópio e GPS, não importa como o celular está preso
- 🎞️ **Combinação com vídeos**: a câmera divide gravações longas em partes; escolha as partes (ou compartilhe com o Telemet pela galeria) e receba um FIT + GPX por parte ou um arquivo único
- 🟩 **Vídeo de mostradores**: velocidade, inclinação, G, altitude, distância e minimapa em fundo verde, no tamanho e na duração de cada parte; coloque sobre o vídeo com chroma key
- 🎛️ **Inclinação e G no DJI Mimo** (opcional): também gravados nos campos de cadência e potência
- 🤖 **Gravação automática** (opcional): a câmera grava quando você sai e para após 3 minutos parado
- 📈 **Altitude barométrica** em celulares com barômetro
- 🔋 **Funciona como app de navegação**: continua gravando com a tela desligada ou outro app na frente
- ⚡ **Primeiro sinal rápido**: o GPS aquece ao abrir o app; lacunas antes do sinal e em túneis são preenchidas
- 🌍 **12 idiomas** · 🔓 **Grátis e código aberto**: sem anúncios, sem conta, sem internet

## Download e instalação

1. No celular, abra a **[versão mais recente](https://github.com/avsessisozx/Telemet/releases/latest)**.
2. Toque em **Telemet-x.y.z.apk** para baixar e abra o arquivo.
3. Se o Android avisar sobre fontes desconhecidas, permita a instalação pelo navegador ou gerenciador de arquivos.
4. Novas versões são instaladas por cima da antiga e mantêm seus passeios.

> **Baixe apenas desta página.** A chave de assinatura é pública (código aberto); um APK de outro lugar pode ser uma cópia adulterada que se instala por cima do app original.

## Início rápido

1. Na **câmera**, ative a conexão sem fio. **Feche o DJI Mimo**: a câmera fala com um controle por vez.
2. Abra o Telemet. O cartão **Configuração** pede as permissões necessárias (localização "Permitir o tempo todo", Bluetooth, notificações, bateria; na Xiaomi também início automático e economia de bateria).
3. Toque em **Iniciar**. Na primeira vez, aprove a conexão na tela da câmera (o código deve ser igual).
4. Quando o ponto do GPS ficar verde, comece a gravar pela câmera ou pelo Telemet.
5. No fim, toque em **Parar**. O passeio aparece em **Meus passeios** e os arquivos FIT/GPX ficam em `Downloads/Telemet`.

## Usar o backup FIT no DJI Studio

Compartilhe o passeio em **Meus passeios** para o computador → abra o vídeo no DJI Studio → **Painel** → **Fonte de dados** → **Importar arquivo FIT** → se precisar, **Alinhamento de tempo**.

---

Câmeras compatíveis, sensores, perguntas frequentes e detalhes técnicos: [página principal em inglês](../README.md).

Licença MIT. Este projeto não é afiliado à DJI. DJI, Osmo e Osmo Action são marcas da SZ DJI Technology Co., Ltd.
