_nq_completion() {
  local current previous
  current="${COMP_WORDS[COMP_CWORD]}"
  previous="${COMP_WORDS[COMP_CWORD-1]}"

  case "$previous" in
    --help)
      COMPREPLY=($(compgen -W "help query catalog cache use-cache clear-cache list-caches connection output parameters parquet" -- "$current"))
      return
      ;;
    -o|--output)
      COMPREPLY=($(compgen -W "json jsonl yaml xml csv markdown" -- "$current"))
      return
      ;;
    --db|--jdbc-driver)
      COMPREPLY=($(compgen -W "h2 mysql mariadb postgresql oracle sqlserver db2 hana informix" -- "$current"))
      return
      ;;
    --anonymous-collections)
      COMPREPLY=($(compgen -W "merge error" -- "$current"))
      return
      ;;
    -p|--cache-dir|--use-cache|--clear-cache)
      COMPREPLY=($(compgen -f -- "$current"))
      return
      ;;
  esac

  if [[ "$current" == -* ]]; then
    COMPREPLY=($(compgen -W "-h --help --version -p -i --input -o --output -c --cache --debug --no-key-inference --cache-dir --use-cache --list-caches --clear-cache --clear-cache-older-than --anonymous-collections --relation-alias --metadata-refresh --metadata-expiry-hours --parquet-root --parquet-record --db --database --host --port --user --password --jdbc-driver --jdbc-class-name --jdbc-database --jdbc-username --jdbc-password" -- "$current"))
  elif [[ $COMP_CWORD -eq 1 ]]; then
    COMPREPLY=($(compgen -W "catalog" -- "$current") $(compgen -f -- "$current"))
  else
    COMPREPLY=($(compgen -f -- "$current"))
  fi
}

complete -F _nq_completion nq
