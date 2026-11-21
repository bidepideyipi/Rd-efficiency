# 将生成器格式的 map SQL：
#   SELECT (expr1) AS COL1, (expr2) AS COL2 FROM ...
# 改写为：
#   SELECT NVL(TO_CHAR((expr1)), '') || CHR(1) || NVL(TO_CHAR((expr2)), '')
#          || CHR(30) || CHR(31) || CHR(30)
#   FROM ...
#
# 不增加外层查询，不按别名引用列，也不需要预先声明 SELECT 列数。
# SQL*Plus 在结果行末补 LF，形成 SQL*Loader 记录结束符 X'1E1F1E0A'。
# 通过 -v columns_file=... 输出实际顶层 SELECT 别名（每行一个）。

BEGIN {
  before_select = 1
  in_select = 0
  after_select = 0
  depth = 0
  item = ""
  item_count = 0
  failed = 0
  number_column_count = split(number_columns_csv, number_column_names, ",")
  for (number_column_idx = 1; number_column_idx <= number_column_count; number_column_idx++) {
    number_column_name = trim(number_column_names[number_column_idx])
    if (number_column_name != "") {
      number_columns[toupper(number_column_name)] = 1
    }
  }
}

function sanitize(raw,    i, c, n) {
  clean = ""
  mask = ""
  for (i = 1; i <= length(raw); i++) {
    c = substr(raw, i, 1)
    n = substr(raw, i + 1, 1)

    if (in_block_comment) {
      clean = clean " "
      mask = mask " "
      if (c == "*" && n == "/") {
        clean = clean " "
        mask = mask " "
        i++
        in_block_comment = 0
      }
      continue
    }
    if (in_single_quote) {
      clean = clean c
      mask = mask " "
      if (c == "'" && n == "'") {
        clean = clean n
        mask = mask " "
        i++
      } else if (c == "'") {
        in_single_quote = 0
      }
      continue
    }
    if (in_double_quote) {
      clean = clean c
      mask = mask " "
      if (c == "\"" && n == "\"") {
        clean = clean n
        mask = mask " "
        i++
      } else if (c == "\"") {
        in_double_quote = 0
      }
      continue
    }

    if (c == "-" && n == "-") {
      break
    }
    if (c == "/" && n == "*") {
      clean = clean "  "
      mask = mask "  "
      i++
      in_block_comment = 1
      continue
    }
    if (c == "'") {
      clean = clean c
      mask = mask " "
      in_single_quote = 1
      continue
    }
    if (c == "\"") {
      clean = clean c
      mask = mask " "
      in_double_quote = 1
      continue
    }

    clean = clean c
    mask = mask c
  }
}

function trim(value) {
  sub(/^[ \t\r\n]+/, "", value)
  sub(/[ \t\r\n]+$/, "", value)
  return value
}

function export_value(expr, alias_name) {
  if (toupper(alias_name) in number_columns) {
    # TM9 避免固定宽度填充；第三参数固定小数点为 "."。
    # Oracle 的 TM9 可能输出 .01 / -.01，再补齐小数点前的 0。
    return "NVL(REGEXP_REPLACE(REGEXP_REPLACE(TO_CHAR(" expr \
      ", 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,'''), '^\\.', '0.'), " \
      "'^-\\.', '-0.'), '')"
  }
  return "NVL(TO_CHAR(" expr "), '')"
}

function flush_item(    value, alias_part, alias_name, expr, formatted_value) {
  value = trim(item)
  item = ""
  if (value == "") {
    return
  }
  if (!match(value, /[ \t\r\n]+[Aa][Ss][ \t\r\n]+[A-Za-z_][A-Za-z0-9_#$]*[ \t\r\n]*$/)) {
    print "cannot parse top-level SELECT item alias: " value > "/dev/stderr"
    failed = 1
    exit 2
  }

  alias_part = substr(value, RSTART, RLENGTH)
  alias_name = alias_part
  sub(/^[ \t\r\n]+[Aa][Ss][ \t\r\n]+/, "", alias_name)
  alias_name = trim(alias_name)
  expr = trim(substr(value, 1, RSTART - 1))

  item_count++
  print alias_name >> columns_file
  formatted_value = export_value(expr, alias_name)
  if (item_count == 1) {
    print "  " formatted_value
  } else {
    print "  || CHR(1) || " formatted_value
  }
}

function consume_select_line(start_at,    i, c, segment_start) {
  segment_start = start_at
  for (i = start_at; i <= length(mask); i++) {
    c = substr(mask, i, 1)
    if (c == "(") {
      depth++
    } else if (c == ")") {
      depth--
      if (depth < 0) {
        print "unbalanced parentheses in SELECT list" > "/dev/stderr"
        failed = 1
        exit 2
      }
    } else if (c == "," && depth == 0) {
      item = item substr(clean, segment_start, i - segment_start)
      flush_item()
      segment_start = i + 1
    }
  }
  item = item substr(clean, segment_start) "\n"
}

{
  if (after_select) {
    print $0
    next
  }

  sanitize($0)
  probe = mask
  sub(/^[ \t]+/, "", probe)

  if (before_select) {
    if (match(probe, /^[Ss][Ee][Ll][Ee][Cc][Tt]([ \t]|$)/)) {
      before_select = 0
      in_select = 1
      print "SELECT"
      # 生成器通常让 SELECT 独占一行；同时兼容同一行紧跟首个表达式。
      select_pos = index(mask, substr(probe, 1, 6))
      consume_select_line(select_pos + 6)
    }
    next
  }

  if (in_select && depth == 0 && probe ~ /^[Ff][Rr][Oo][Mm]([ \t]|$)/) {
    flush_item()
    # SQL*Plus 会在每个查询结果行末追加 LF；此前加 1E 1F 1E，
    # 最终形成 SQL*Loader 自定义记录结束符 X'1E1F1E0A'。
    # 字段内普通 CR/LF 因此不再被误判为记录边界。
    print "  || CHR(30) || CHR(31) || CHR(30)"
    in_select = 0
    after_select = 1
    print $0
    next
  }

  if (in_select) {
    consume_select_line(1)
  }
}

END {
  if (failed) {
    exit 2
  }
  if (before_select || in_select || item_count == 0) {
    print "cannot locate complete top-level SELECT ... FROM in map SQL" > "/dev/stderr"
    exit 2
  }
  print ";"
}
