# NQL shell completion

Completion definitions cover the stable command-line options and help topics.
They do not inspect live databases or input files.

## Bash

For the current shell:

```bash
source util/completions/nql.bash
```

For future shells, copy `nql.bash` to a completion directory used by your Bash
installation.

## Zsh

Place `_nql` in a directory on `$fpath`, then rebuild the completion cache:

```zsh
mkdir -p ~/.zsh/completions
cp util/completions/_nql ~/.zsh/completions/
fpath=(~/.zsh/completions $fpath)
autoload -Uz compinit && compinit
```

## Fish

```fish
mkdir -p ~/.config/fish/completions
cp util/completions/nql.fish ~/.config/fish/completions/
```
