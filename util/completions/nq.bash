_nq_completion() {
  local current previous
  current="${COMP_WORDS[COMP_CWORD]}"
  previous="${COMP_WORDS[COMP_CWORD-1]}"

  case "$previous" in
    help|--help)
      COMPREPLY=($(compgen -W "help run convert catalog cache connection output state parameters parquet" -- "$current"))
      return
      ;;
    -o|--output|--report-format)
      COMPREPLY=($(compgen -W "json jsonl yaml xml csv tsv toml markdown" -- "$current"))
      return
      ;;
    --input-format)
      COMPREPLY=($(compgen -W "json jsonl yaml xml csv tsv toml parquet" -- "$current"))
      return
      ;;
    --db|--jdbc-driver)
      COMPREPLY=($(compgen -W "h2 mysql mariadb postgresql oracle sqlserver db2 hana informix" -- "$current"))
      return
      ;;
    --script-file|--input-file|-p|--properties|--state-dir)
      COMPREPLY=($(compgen -f -- "$current"))
      return
      ;;
  esac

  if [[ "$current" == -* ]]; then
    COMPREPLY=($(compgen -W "-h --help --version --script-file --script-text --input-file --input-format --param -p --properties -o --output --report-format --state-dir --cache --debug --no-key-inference --pattern --name --all --older-than --parquet-root --parquet-record --db --database --host --port --user --password --jdbc-driver --jdbc-class-name --jdbc-database --jdbc-username --jdbc-password" -- "$current"))
  elif [[ $COMP_CWORD -eq 1 ]]; then
    COMPREPLY=($(compgen -W "run convert catalog cache help" -- "$current"))
  elif [[ ${COMP_WORDS[1]} == cache && $COMP_CWORD -eq 2 ]]; then
    COMPREPLY=($(compgen -W "load use list clear" -- "$current"))
  fi
}

complete -F _nq_completion nq
