# AstroStack 🔭

App Android de astrofotografia com **empilhamento ao vivo** (live stacking), feito pela SW Games Studios.

## O que ele faz
- Câmera manual: exposição longa (até o máximo do seu celular), ISO e foco no infinito.
- Tira várias fotos seguidas e, a cada foto, **detecta as estrelas, alinha e soma** na pilha.
- A imagem vai ficando mais limpa na tela enquanto você fotografa.
- Remove o brilho da poluição luminosa e neutraliza a cor do céu.
- Remove pixels quentes.
- Salva o resultado em `Imagens/AstroStack`.

## Como usar
1. Coloque o celular num tripé (ou bem apoiado) apontando pro céu.
2. Escolha exposição (ex.: 10 s), ISO (ex.: 1600) e quantas fotos (ex.: 20).
3. Toque em **▶ INICIAR** e não encoste no celular.
4. Ajuste o **Realce** no final e toque em **💾 Salvar**.

## Como compilar
O GitHub Actions compila sozinho a cada push no branch `astrostack`
e publica o APK em **Releases**.

## Estrutura
- `Stacker.kt` — detecção de estrelas, alinhamento, empilhamento e realce (Kotlin puro).
- `MainActivity.kt` — câmera (Camera2) e tela.
