package compare

import (
	"context"
	"database/sql"
	"fmt"
	"math"
	"math/big"
	"strconv"
	"strings"
)

// Column 描述结果集的一列。
type Column struct {
	Name string `json:"name"`
	Type string `json:"type"`
}

// ResultSet 为 SQL 查询结果集的标准表示，用于结构与数据比对。
type ResultSet struct {
	Columns []Column   `json:"columns"`
	Rows    [][]string `json:"rows"`
	Nulls   [][]bool   `json:"nulls,omitempty"`
}

// Capture 从 *sql.Rows 读取全部数据，构造标准结果集。
// 保留 NULL 标记，与用于展示的文本分开。
func Capture(rows *sql.Rows) (ResultSet, error) {
	names, err := rows.Columns()
	if err != nil {
		return ResultSet{}, err
	}
	types, err := rows.ColumnTypes()
	if err != nil {
		return ResultSet{}, err
	}
	cols := make([]Column, 0, len(names))
	for i := range names {
		cols = append(cols, Column{Name: names[i], Type: types[i].DatabaseTypeName()})
	}
	result := ResultSet{Columns: cols, Rows: [][]string{}}
	for rows.Next() {
		raw := make([]any, len(cols))
		ptrs := make([]any, len(cols))
		for i := range raw {
			ptrs[i] = &raw[i]
		}
		if err := rows.Scan(ptrs...); err != nil {
			return ResultSet{}, err
		}
		row := make([]string, len(cols))
		nulls := make([]bool, len(cols))
		for i, v := range raw {
			row[i] = cellString(v)
			nulls[i] = v == nil
		}
		result.Rows = append(result.Rows, row)
		result.Nulls = append(result.Nulls, nulls)
	}
	if err := rows.Err(); err != nil {
		return ResultSet{}, err
	}
	return result, nil
}

// cellString 将 Scan 得到的值序列化为字符串。
func cellString(v any) string {
	switch t := v.(type) {
	case nil:
		return "NULL"
	case []byte:
		return string(t)
	case string:
		return t
	default:
		return fmt.Sprintf("%v", v)
	}
}

// EqualResultSets 比对期望与实际结果集：结构（列数/列名/列型）+ 行数 + 数据（数值宽容差）+ 行序。
// 返回是否一致及首个差异的可读描述（一致时 diff 为空）。
func EqualResultSets(expected, actual ResultSet, orderSensitive bool) (bool, string) {
	return EqualResultSetsContext(context.Background(), expected, actual, orderSensitive)
}

// EqualResultSetsContext checks the case deadline during potentially large comparisons.
func EqualResultSetsContext(ctx context.Context, expected, actual ResultSet, orderSensitive bool) (bool, string) {
	if ctx.Err() != nil {
		return false, "结果比对超时"
	}
	if len(expected.Columns) != len(actual.Columns) {
		return false, fmt.Sprintf("列数不一致：期望 %d 列，实际 %d 列", len(expected.Columns), len(actual.Columns))
	}
	for i := range expected.Columns {
		if expected.Columns[i].Name != actual.Columns[i].Name {
			return false, fmt.Sprintf("第 %d 列列名不一致：期望 %q，实际 %q", i+1, expected.Columns[i].Name, actual.Columns[i].Name)
		}
		if normalizeType(expected.Columns[i].Type) != normalizeType(actual.Columns[i].Type) {
			return false, fmt.Sprintf("第 %d 列类型不一致：期望 %s，实际 %s", i+1, expected.Columns[i].Type, actual.Columns[i].Type)
		}
	}
	if len(expected.Rows) != len(actual.Rows) {
		return false, fmt.Sprintf("行数不一致：期望 %d 行，实际 %d 行", len(expected.Rows), len(actual.Rows))
	}

	for _, rows := range [][][]string{expected.Rows, actual.Rows} {
		for _, row := range rows {
			if len(row) != len(expected.Columns) {
				return false, "结果行列数与列定义不一致"
			}
		}
	}
	if orderSensitive {
		for r := range expected.Rows {
			if ctx.Err() != nil || !equalRow(expected, r, actual, r) {
				return false, fmt.Sprintf("第 %d 行不一致", r+1)
			}
		}
		return true, ""
	}

	// 先按必须精确相等的列分组。没有近似列时，比较每组的行数即可保留重复行语义。
	actualGroups := make(map[string][]int)
	for i := range actual.Rows {
		if ctx.Err() != nil {
			return false, "结果比对超时"
		}
		key := exactKey(actual, i)
		actualGroups[key] = append(actualGroups[key], i)
	}
	expectedGroups := make(map[string][]int)
	for i := range expected.Rows {
		if ctx.Err() != nil {
			return false, "结果比对超时"
		}
		key := exactKey(expected, i)
		expectedGroups[key] = append(expectedGroups[key], i)
	}
	hasTolerance := false
	for _, col := range expected.Columns {
		hasTolerance = hasTolerance || approximate(col.Type)
	}
	for key, exp := range expectedGroups {
		act := actualGroups[key]
		if len(exp) != len(act) {
			return false, fmt.Sprintf("第 %d 行的匹配行数不一致", exp[0]+1)
		}
		if hasTolerance && !matchRows(ctx, expected, actual, exp, act) {
			return false, fmt.Sprintf("以第 %d 行开头的分组无法逐行匹配", exp[0]+1)
		}
	}
	return true, ""
}

// matchRows 使用增广路径匹配。容差相等不具传递性，不能通过字典排序或贪心配对。
func matchRows(ctx context.Context, expected, actual ResultSet, exp, act []int) bool {
	// The typical output is exactly equal. Avoid quadratic matching in that case.
	exact := make(map[string]int, len(exp))
	for _, e := range exp {
		exact[rowKey(expected, e, true)]++
	}
	allExact := true
	for _, a := range act {
		key := rowKey(actual, a, true)
		exact[key]--
		if exact[key] < 0 {
			allExact = false
			break
		}
	}
	if allExact {
		return true
	}

	matched := make([]int, len(act))
	seen := make([]int, len(act))
	var augment func(int, int) bool
	augment = func(row, visit int) bool {
		for i, a := range act {
			if ctx.Err() != nil {
				return false
			}
			if matched[i] == 0 && seen[i] != visit && equalRow(expected, row, actual, a) {
				matched[i] = row + 1
				return true
			}
		}
		for i, a := range act {
			if ctx.Err() != nil {
				return false
			}
			if seen[i] == visit || !equalRow(expected, row, actual, a) {
				continue
			}
			seen[i] = visit
			if augment(matched[i]-1, visit) {
				matched[i] = row + 1
				return true
			}
		}
		return false
	}
	for i, row := range exp {
		if !augment(row, i+1) {
			return false
		}
	}
	return true
}

func (rs ResultSet) isNull(row, col int) bool {
	return row < len(rs.Nulls) && col < len(rs.Nulls[row]) && rs.Nulls[row][col]
}

func exactKey(rs ResultSet, row int) string { return rowKey(rs, row, false) }

func rowKey(rs ResultSet, row int, includeApproximate bool) string {
	// Length prefixes keep null, text, and arbitrary binary strings distinct.
	var key strings.Builder
	for i, col := range rs.Columns {
		if rs.isNull(row, i) {
			key.WriteByte('-')
			continue
		}
		v := ""
		if includeApproximate || !approximate(col.Type) {
			v = strings.TrimSpace(rs.Rows[row][i])
		}
		key.WriteString(strconv.Itoa(len(v)))
		key.WriteByte(':')
		key.WriteString(v)
	}
	return key.String()
}

func equalRow(expected ResultSet, e int, actual ResultSet, a int) bool {
	for i, col := range expected.Columns {
		en, an := expected.isNull(e, i), actual.isNull(a, i)
		if en != an || (!en && !cellEqual(expected.Rows[e][i], actual.Rows[a][i], col.Type)) {
			return false
		}
	}
	return true
}

func approximate(columnType string) bool {
	switch normalizeType(columnType) {
	case "FLOAT", "DOUBLE", "REAL", "DECIMAL", "NUMERIC":
		return true
	default:
		return false
	}
}

func cellEqual(a, b, columnType string) bool {
	a, b = strings.TrimSpace(a), strings.TrimSpace(b)
	if a == b {
		return true
	}
	if !approximate(columnType) {
		return false
	}
	switch normalizeType(columnType) {
	case "DECIMAL", "NUMERIC":
		// 保留十进制精度，避免 float64 把不同值舍入成同一个数。
		x, ok := new(big.Rat).SetString(a)
		y, ok2 := new(big.Rat).SetString(b)
		if !ok || !ok2 {
			return false
		}
		scale := new(big.Rat).Abs(y)
		if scale.Cmp(big.NewRat(1, 1)) < 0 {
			scale.SetInt64(1)
		}
		return new(big.Rat).Abs(new(big.Rat).Sub(x, y)).Cmp(scale.Quo(scale, big.NewRat(1_000_000_000, 1))) <= 0
	default:
		x, err := strconv.ParseFloat(a, 64)
		y, err2 := strconv.ParseFloat(b, 64)
		if err != nil || err2 != nil || math.IsInf(x, 0) || math.IsInf(y, 0) {
			return false
		}
		return math.Abs(x-y) <= 1e-9*math.Max(1, math.Abs(y))
	}
}

// normalizeType 宽松归一化列型：大写、去长度/精度修饰，并将常见等价类型族归并。
func normalizeType(t string) string {
	upper := strings.ToUpper(strings.TrimSpace(t))
	if i := strings.IndexByte(upper, '('); i >= 0 {
		upper = upper[:i]
	}
	upper = strings.TrimSpace(strings.TrimSuffix(strings.TrimSuffix(upper, " ZEROFILL"), " UNSIGNED"))
	switch upper {
	case "INTEGER":
		return "INT"
	case "BOOL", "BOOLEAN":
		return "TINYINT"
	}
	return upper
}
