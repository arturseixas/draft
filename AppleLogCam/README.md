# Apple Log Cam

App Android (Kotlin, sem dependências externas) que grava 4K30 HEVC Main10 com a curva Apple Log, usando Camera2 no Pixel 10 Pro.

## Como abrir

1. Android Studio (versão recente), File > Open, escolha a pasta `AppleLogCam`.
2. Deixe o Gradle sincronizar (o wrapper aponta para o Gradle 8.9; o Studio baixa sozinho).
3. Conecte o Pixel 10 Pro com depuração USB e rode o módulo `app`.
4. Os vídeos vão para `Movies/AppleLogCam`, nomeados `AppleLog_AAAAMMDD_HHMMSS.mp4`.

## O que o app faz de verdade

O Pixel não grava Apple Log por conta própria. O Apple Log é um perfil da Apple (curva + gamut Rec.2020) e o Camera2 do Pixel só oferece SDR e HLG10 em 10 bits. Então o app faz a conversão:

```
Camera2 (HLG10, BT.2020, 10 bits)
  -> shader: YUV -> RGB (matriz BT.2020) -> HLG inverso (luz de cena linear)
  -> reflectância (sinal HLG 0,75 = branco difuso = 1,0)
  -> curva Apple Log do white paper (constantes oficiais)
  -> EGL RGBA1010102 -> HEVC Main10 -> MP4 (+ AAC)
```

- A curva usa as constantes do white paper da Apple: R0 = -0,05641088, Rt = 0,01, c = 47,28711236, beta = 0,00964052, gamma = 0,08550479, delta = 0,69336945. O código dá 0,48827 para cinza 18 %, igual à referência. Apple Log chega a 1,0 em R ≈ 12.
- Gamut: Apple Log usa primárias Rec.2020, as mesmas do HLG10 da câmera. Não há matriz de cor.
- O monitor da tela mostra uma visão SDR Rec.709 (botão "Monitor: 709/Log"). O arquivo sempre sai em Apple Log.

## Limites que você precisa saber

- A faixa dinâmica é a que o ISP do Pixel entrega em HLG10 (já com tone mapping do Google). É um arquivo no formato Apple Log, não os 12 stops de um iPhone gravando Apple Log nativo. Com HLG10 o topo do sinal fica em R ≈ 3,8 (P ≈ 0,86).
- O MediaCodec não tem rótulo de transferência "Apple Log". O arquivo sai marcado como BT.2020 + HLG. No DaVinci Resolve, Premiere ou Final Cut, ignore a tag e defina o espaço de cor de entrada do clipe como Apple Log (Rec.2020).
- Só uma lente (a primeira câmera traseira com HLG10), sem troca de lente nem zoom.
- Exposição: auto ou manual (ISO e obturador). Sem foco manual, sem balanço de branco travado.
- Não compilei nem rodei em aparelho. Os pontos que mais provavelmente precisam de ajuste estão na seção abaixo.

## Se algo sair errado

| Sintoma | Onde mexer |
|---|---|
| Preto levantado ou contraste estranho no monitor 709 | `readFullRange` em `CaptureEngine.kt` (a faixa full/limited do YUV vem do dataspace do frame; `DEFAULT_FULL_RANGE_IF_UNKNOWN` vale quando o dataspace não vem) |
| Cores trocadas ou verde/rosa | `LogRenderer.kt`: o shader tem duas variantes (EXT_YUV_target e samplerExternalOES). O logcat (tag `AppleLogCam`) diz qual está em uso |
| "A câmera recusou a sessão HLG10 em 4K30" | `CameraController.choose()`: teste outro tamanho ou FPS |
| Erro ao configurar o encoder | `VideoEncoder.kt`: o app já tenta sem nível High Tier 5.1; reduza `BITRATE` em `CaptureEngine.kt` |
| Áudio adiantado ou atrasado | `AudioEncoder.kt`: o PTS usa `elapsedRealtimeNanos`; ajuste um deslocamento fixo aí |
| Vídeo de cabeça para baixo | `orientationHint` em `CaptureEngine.kt` |

## Arquivos

- `CameraController.kt`: Camera2, perfil HLG10, exposição manual.
- `LogRenderer.kt`: shader HLG -> Apple Log e monitor 709.
- `EglCore.kt`: EGL 10 bits e surfaces.
- `CaptureEngine.kt`: loop de frames, gravação, MediaStore.
- `VideoEncoder.kt`, `AudioEncoder.kt`, `MuxerSink.kt`: HEVC Main10, AAC e o MP4.
- `MainActivity.kt`: interface.
