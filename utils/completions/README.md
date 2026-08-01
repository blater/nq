# NQ shell completion

Completion definitions cover the stable command-line options and help topics.
They do not inspect live databases or input files.

## Bash

For the current shell:

```bash
source utils/completions/nq.bash
```

For future shells, copy `nq.bash` to a completion directory used by your Bash
installation.

## Zsh

Place `_nq` in a directory on `$fpath`, then rebuild the completion cache:

```zsh
mkdir -p ~/.zsh/completions
cp utils/completions/_nq ~/.zsh/completions/
fpath=(~/.zsh/completions $fpath)
autoload -Uz compinit && compinit
```

## Fish

```fish
mkdir -p ~/.config/fish/completions
cp utils/completions/nq.fish ~/.config/fish/completions/
```
