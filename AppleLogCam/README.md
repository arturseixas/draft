# Apple Log Cam

Câmera de cinema para o Pixel 10 Pro (Kotlin, Camera2, sem dependências externas). Grava HEVC Main10 em Apple Log, HLG ou Rec.709.

APK de debug: https://github.com/arturseixas/draft/releases/download/apple-log-cam-latest/AppleLogCam-debug.apk
(o GitHub Actions refaz o APK a cada push na branch `apple-log-cam`).

## Funções

- Lentes: todas as traseiras pela câmera lógica (.5, 1, 2, 5 e zoom de pinça), mais a frontal. As paradas vêm da distância focal de cada lente física.
- Formato: 4K UHD ou HD 1080; 24, 25, 30 ou 60 fps (só os que a câmera aceita naquela resolução); bitrate de 50 a 200 Mb/s; áudio AAC 48 kHz estéreo.
- Perfis: Apple Log, HLG e Rec.709 (com ombro suave nos altos).
- Exposição: automática com EV e AE lock, ou manual com ISO e obturador em ângulo (180°) ou velocidade (1/48).
- Balanço de branco: auto e presets 3200K, 4000K, 5600K, 6500K, 7500K, com trava.
- Foco: AF contínuo, toque para focar e medir, toque longo trava AE/AF, toque duplo volta ao AF-C, foco manual com lupa 3× e peaking automáticos.
- Monitor: LUT Rec.709 ligada ou desligada, zebra (70 a 100%), focus peaking, false color, grade, linhas de corte 2.39, 1.85, 4:3, 1:1 e 9:16.
- Imagem: estabilização, nitidez, redução de ruído, anti-flicker 50/60 Hz, lanterna.
- Gravação: timecode, medidor de áudio L/R, nome de rolo e clipe (A001C001), miniatura do último clipe, botões de volume para gravar, minutos livres e bateria na tela.

## Como funciona o Apple Log aqui

O Pixel não grava Apple Log. O app pega o HLG10 (BT.2020, 10 bits) do sensor, converte para luz de cena linear (sinal HLG 0,75 = branco difuso = 1,0) e aplica a curva do white paper da Apple (cinza 18% vira 0,4883). A faixa dinâmica é a que o ISP do Pixel entrega em HLG10. Na frontal, se não houver HLG10, a entrada é SDR e o resultado tem menos latitude.

O MediaCodec não tem rótulo para Apple Log, então o arquivo sai marcado como BT.2020 + HLG. No DaVinci Resolve, Premiere ou Final Cut, defina o espaço de cor de entrada do clipe como Apple Log (Rec.2020).

## Arquivos

- `CameraController.kt`: Camera2, descoberta das câmeras e lentes, todos os controles.
- `CaptureEngine.kt`: loop de frames, rotação, gravação e MediaStore.
- `LogRenderer.kt`: shader de entrada (HLG/SDR) para Apple Log, HLG ou 709, e as ferramentas do monitor.
- `MainActivity.kt`: interface e gestos. `Views.kt`: régua, chips, botão de gravar, overlay, medidor e seletor de lentes.
- `Settings.kt`: preferências salvas. `VideoEncoder.kt`, `AudioEncoder.kt`, `MuxerSink.kt`, `EglCore.kt`: codificação e EGL.

Logs no logcat com a tag `AppleLogCam`.
