mod bigquery;
use std::collections::HashSet;

use bigquery::BigQueryDialect;
use pyo3::exceptions::PyRuntimeError;
use pyo3::prelude::*;
use sqlparser::ast::{
    Expr, Ident, Query, Select, SelectItem, SetExpr, Statement, TableAlias, TableFactor, With,
};
use sqlparser::parser::Parser;

#[derive(Debug, PartialEq)]
struct Context {
    aliases: HashSet<String>,
    inputs: HashSet<String>,
    output: Option<String>,
}

impl Context {
    fn new() -> Context {
        Context {
            aliases: HashSet::new(),
            inputs: HashSet::new(),
            output: None,
        }
    }

    fn add_table_alias(&mut self, alias: &TableAlias) {
        self.aliases.insert(alias.name.value.clone());
    }

    fn add_ident_alias(&mut self, alias: &Ident) {
        self.aliases.insert(alias.value.clone());
    }

    fn add_input(&mut self, table: &String) {
        if !self.aliases.contains(table) {
            self.inputs.insert(table.clone());
        }
    }

    fn set_output(&mut self, output: &String) {
        self.output = Some(output.clone());
    }
}

#[pyclass]
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct DbTableMeta {
    #[pyo3(get)]
    database: Option<String>,
    #[pyo3(get)]
    schema: Option<String>,
    #[pyo3(get)]
    name: String,
    // ..columns
}

#[pymethods]
impl DbTableMeta {
    #[new]
    fn new(name: String) -> Self {
        let mut split = name.split(".").map(|x| String::from(x)).collect::<Vec<String>>();
        split.reverse();
        DbTableMeta {
            database: split.get(2).cloned(),
            schema: split.get(1).cloned(),
            name: split.get(0).unwrap().clone(),
        }
    }
    pub fn qualified_name(&self) -> String {
        format!(
            "{}{}{}",
            self.database.as_ref().unwrap_or(&String::from("")),
            self.schema.as_ref().unwrap_or(&String::from("")),
            self.name
        )
    }
}

#[pyclass]
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct SqlMeta {
    #[pyo3(get)]
    pub in_tables: Vec<DbTableMeta>,
    #[pyo3(get)]
    pub out_tables: Vec<DbTableMeta>,
}

impl From<Context> for SqlMeta {
    fn from(ctx: Context) -> Self {
        let mut inputs: Vec<String> = ctx.inputs.into_iter().collect();
        let outputs: Vec<String> = if ctx.output.is_some() {
            vec![ctx.output.unwrap()]
        } else {
            vec![]
        };
        inputs.sort();
        SqlMeta {
            in_tables: inputs.iter().map(|x| DbTableMeta::new(x.clone())).collect(),
            out_tables: outputs.iter().map(|x| DbTableMeta::new(x.clone())).collect()
        }
    }
}

fn parse_with(with: &With, context: &mut Context) -> Result<(), String> {
    for cte in &with.cte_tables {
        context.add_table_alias(&cte.alias);
        parse_query(&cte.query, context)?;
    }
    Ok(())
}

fn parse_table_factor(table: &TableFactor, context: &mut Context) -> Result<(), String> {
    match table {
        TableFactor::Table { name, .. } => {
            context.add_input(&name.to_string());
            Ok(())
        }
        TableFactor::Derived {
            lateral: _,
            subquery,
            alias,
        } => {
            parse_query(subquery, context)?;
            if let Some(a) = alias {
                context.add_table_alias(a);
            }
            Ok(())
        }
        _ => Err(format!(
            "TableFactor other than table or subquery not implemented: {table}"
        )),
    }
}

fn get_table_name_from_table_factor(table: &TableFactor) -> Result<String, String> {
    if let TableFactor::Table { name, .. } = table {
        Ok(name.to_string())
    } else {
        Err(format!(
            "Name can be got only from simple table, got {table}"
        ))
    }
}

/// Process expression in case where we want to extract lineage (for eg. in subqueries)
/// This means most enum types are untouched, where in other contexts they'd be processed.
fn parse_expr(expr: &Expr, context: &mut Context) -> Result<(), String> {
    match expr {
        Expr::Subquery(query) => {
            parse_query(query, context)?;
        }

        Expr::Identifier(_) => {}
        Expr::CompoundIdentifier(_) => {}
        Expr::IsNull(_) => {}
        Expr::IsNotNull(_) => {}
        Expr::IsDistinctFrom(_, _) => {}
        Expr::IsNotDistinctFrom(_, _) => {}
        Expr::InList { .. } => {}
        Expr::InSubquery {
            expr: _,
            subquery,
            negated: _,
        } => {
            parse_query(subquery, context)?;
        }
        Expr::InUnnest { .. } => {}
        Expr::Between { .. } => {}
        Expr::BinaryOp { left, op: _, right } => {
            parse_expr(left, context)?;
            parse_expr(right, context)?;
        }
        Expr::UnaryOp { op: _, expr } => {
            parse_expr(expr, context)?;
        }
        Expr::Cast { .. } => {}
        Expr::TryCast { .. } => {}
        Expr::Extract { .. } => {}
        Expr::Substring { .. } => {}
        Expr::Trim { .. } => {}
        Expr::Collate { .. } => {}
        Expr::Nested(_) => {}
        Expr::Value(_) => {}
        Expr::TypedString { .. } => {}
        Expr::MapAccess { .. } => {}
        Expr::Function(_) => {}
        Expr::Case {
            operand: _,
            conditions,
            results: _,
            else_result: _,
        } => {
            for condition in conditions {
                parse_expr(condition, context)?;
            }
        }
        Expr::Exists(_) => {}
        Expr::ListAgg(_) => {}
        Expr::GroupingSets(_) => {}
        Expr::Cube(_) => {}
        Expr::Rollup(_) => {}
        Expr::Tuple(_) => {}
        Expr::ArrayIndex { .. } => {}
        Expr::Array(_) => {}
    }
    Ok(())
}

fn parse_select(select: &Select, context: &mut Context) -> Result<(), String> {
    for projection in &select.projection {
        match projection {
            SelectItem::UnnamedExpr(expr) => {
                parse_expr(&expr, context)?;
            }
            SelectItem::ExprWithAlias { expr, alias } => {
                parse_expr(&expr, context)?;
                context.add_ident_alias(&alias);
            }
            _ => {}
        }
    }

    for table in &select.from {
        parse_table_factor(&table.relation, context)?;
        for join in &table.joins {
            parse_table_factor(&join.relation, context)?;
        }
    }
    Ok(())
}

fn parse_setexpr(setexpr: &SetExpr, context: &mut Context) -> Result<(), String> {
    match setexpr {
        SetExpr::Select(select) => parse_select(&select, context)?,
        SetExpr::Values(_) => (),
        SetExpr::Insert(stmt) => parse_stmt(stmt, context)?,
        SetExpr::Query(q) => parse_query(q, context)?,
        SetExpr::SetOperation {
            op: _,
            all: _,
            left,
            right,
        } => {
            parse_setexpr(&left, context)?;
            parse_setexpr(&right, context)?;
        }
    };
    Ok(())
}

fn parse_query(query: &Query, context: &mut Context) -> Result<(), String> {
    match &query.with {
        Some(with) => parse_with(&with, context)?,
        None => (),
    };

    parse_setexpr(&query.body, context)?;
    Ok(())
}

fn parse_stmt(stmt: &Statement, context: &mut Context) -> Result<(), String> {
    match stmt {
        Statement::Query(query) => {
            parse_query(query, context)?;
            Ok(())
        }
        Statement::Insert {
            or: _,
            table_name,
            columns: _,
            overwrite: _,
            source,
            partitioned: _,
            after_columns: _,
            table: _,
            on: _,
        } => {
            parse_query(source, context)?;
            context.set_output(&table_name.to_string());
            Ok(())
        }
        Statement::Merge {
            table,
            source,
            alias,
            on: _,
            clauses: _,
        } => {
            let table_name = get_table_name_from_table_factor(table)?;
            context.set_output(&table_name);
            parse_setexpr(source, context)?;

            if let Some(a) = alias {
                context.add_table_alias(a);
            }

            Ok(())
        }
        _ => Err(String::from("not a insert")),
    }
}

pub fn parse_sql(sql: &str) -> Result<SqlMeta, String> {
    let dialect = BigQueryDialect;
    let ast = match Parser::parse_sql(&dialect, sql) {
        Ok(k) => k,
        Err(e) => return Err(e.to_string().to_owned()),
    };

    if ast.is_empty() {
        return Err(String::from("Empty statement list"));
    }

    let mut context = Context::new();
    let stmt = ast.first();

    parse_stmt(stmt.unwrap(), &mut context)?;
    Ok(SqlMeta::from(context))
}

// Parses SQL.
#[pyfunction]
fn parse(sql: &str) -> PyResult<SqlMeta> {
    match parse_sql(sql) {
        Ok(ok) => Ok(ok),
        Err(err) => Err(PyRuntimeError::new_err(err)),
    }
}

/// A Python module implemented in Rust.
#[pymodule]
fn openlineage_sql(_py: Python, m: &PyModule) -> PyResult<()> {
    m.add_function(wrap_pyfunction!(parse, m)?)?;
    m.add_class::<SqlMeta>()?;
    m.add_class::<DbTableMeta>()?;
    Ok(())
}
