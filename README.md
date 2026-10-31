# Animes TV

App para Android TV / Google TV que mostra a programação semanal dos animes (horário de Brasília, versão legendada do Crunchyroll), permite marcar favoritos e abre o anime no app do Crunchyroll.

- Programação: [AniList](https://anilist.co) (API pública)
- Episódios do dia dos favoritos são enviados ao "Continuar assistindo" da tela inicial (experimental)
- Cada envio para a branch `main` gera um APK novo em **Releases**

## Instalar na TV

1. Na TV, instale o app **Downloader** (Play Store) e permita que ele instale apps desconhecidos.
2. Abra o Downloader e digite o endereço:
   `https://github.com/idelmiro/animes-tv/releases/latest/download/animes-tv.apk`
   (funciona com o repositório público)
3. Instale. O app aparece em "Apps" como **Animes TV**.

Depois disso, novas versões podem ser instaladas pelo próprio app: **Ajustes → Verificar atualização**.

## Uso

- Setas esquerda/direita na fileira de dias trocam o dia.
- OK em um anime: abrir no Crunchyroll ou marcar/desmarcar favorito.
- **♡ Todos / ♥ Só favoritos**: filtra a grade.
- **Ajustes**: atraso do legendado (padrão 60 min após o Japão), diagnóstico e atualização.
