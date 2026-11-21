# Galeria IA

App Android pessoal: você descreve fotos em português ("prints de conversa de 2024",
"cachorro na praia", "comida no mês passado") e ele encontra as fotos da galeria que batem,
para você mandar para a **Lixeira** ou **mover para uma pasta**.

Tudo roda no celular. Só o download inicial do modelo de IA usa internet.

## Como rodar no seu celular

1. **Android Studio**: abra a pasta `GaleriaIA` (File → Open). Espere o "Gradle sync" terminar
   (na primeira vez ele baixa as bibliotecas, demora alguns minutos).
   Se ele sugerir atualizar o "Android Gradle Plugin", pode aceitar.
2. **No S25, ative o modo desenvolvedor**: Configurações → Sobre o telefone →
   Informações do software → toque 7 vezes em "Número de compilação".
3. **Ative a depuração USB**: Configurações → Opções do desenvolvedor → Depuração USB.
4. Conecte o celular no PC pelo cabo, aceite o aviso "Permitir depuração USB" no celular.
5. No Android Studio, escolha o S25 na lista de dispositivos (topo) e clique em ▶ **Run**.

## Primeiro uso

1. **Permitir acesso** às fotos (escolha "Permitir tudo", não "Selecionar fotos").
2. **Baixar a IA** (~450 MB, use Wi-Fi): modelo CLIP + tradutor português→inglês.
3. **Indexar galeria**: a IA analisa cada foto uma vez. Com milhares de fotos leva alguns
   minutos. Deixe o app aberto (a tela fica ligada sozinha). Dá para pausar e continuar.
   Depois, ao abrir o app, ele só indexa as fotos novas.
4. **Buscar**: escreva a descrição e toque em Buscar.

## Na tela de resultados

- Todas as fotos encontradas já vêm **marcadas**. Toque para desmarcar as que não quer.
- **Segure** uma foto para vê-la grande.
- A **barra** controla o rigor: para a esquerda aparecem mais fotos (com mais erros),
  para a direita aparecem menos fotos (mais certeiras).
- **Lixeira**: o Android pede confirmação e as fotos ficam 30 dias recuperáveis
  (Galeria → menu → Lixeira).
- **Mover p/ pasta**: crie uma pasta nova (fica em `Pictures/<nome>`) ou escolha um álbum
  existente. O Android pede confirmação.

## Datas que a busca entende

`hoje`, `ontem`, `últimos 30 dias`, `últimos 6 meses`, `semana passada`, `mês passado`,
`ano passado`, `este ano`, `este mês`, `2024`, `de 2022 a 2024`, `entre 2022 e 2024`,
`março de 2025`, `03/2025`, `15/03/2025`, `dezembro` (dezembro de qualquer ano).
Também dá para escolher o período no botão de calendário (ele tem prioridade sobre o texto).

Só data, sem descrição ("fotos de 2023"), funciona mesmo sem indexar.

## Como funciona (mapa do código)

| Arquivo | O que faz |
|---|---|
| `ml/ClipModel.kt` | Roda o CLIP (ONNX Runtime): foto → vetor, texto → vetor |
| `ml/ClipTokenizer.kt` | Transforma o texto em tokens para o CLIP |
| `ml/ModelFiles.kt` | Quais arquivos de modelo baixar e de onde (Hugging Face) |
| `ml/PtEnTranslator.kt` | Tradução offline PT→EN (Google ML Kit) |
| `search/QueryParser.kt` | Separa a data da descrição ("praia em 2024" → "praia" + 2024) |
| `data/MediaRepository.kt` | Lê a galeria, manda para a Lixeira, move para pasta |
| `data/EmbeddingStore.kt` | Banco SQLite com o vetor de cada foto (o "índice") |
| `ui/MainViewModel.kt` | Lógica: indexação, busca, ações |
| `ui/HomeScreen.kt`, `ui/ResultsScreen.kt` | As telas |

A busca compara o vetor do texto com o vetor de cada foto (similaridade de cosseno).
Quanto mais alto, mais a foto combina com a descrição.

## Ajustes fáceis

- **Gírias**: `GLOSSARY` em `MainViewModel.kt` (ex.: "print" → "captura de tela").
- **Modelo menor/mais rápido**: em `ModelFiles.kt`, troque `onnx/vision_model.onnx` por
  `onnx/vision_model_quantized.onnx` (~89 MB, um pouco menos preciso). Depois apague os
  dados do app (Configurações → Apps → Galeria IA → Armazenamento → Limpar dados) para
  reindexar.

## Próximos passos

- Vídeos em uma aba separada (mesma ideia, analisando alguns quadros de cada vídeo).
- Busca por "fotos parecidas com esta" (o CLIP já permite).
