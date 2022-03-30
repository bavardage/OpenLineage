use openlineage_sql::{parse_sql, SqlMeta};
use sqlparser::dialect::PostgreSqlDialect;

#[macro_use]
mod test_utils;
use test_utils::*;

#[test]
fn insert_values() {
    assert_eq!(
        test_sql("INSERT INTO TEST VALUES(1)",),
        SqlMeta {
            in_tables: vec![],
            out_tables: table("TEST")
        }
    );
}

#[test]
fn insert_cols_values() {
    assert_eq!(
        test_sql("INSERT INTO tbl(col1, col2) VALUES (1, 2), (2, 3)",),
        SqlMeta {
            in_tables: vec![],
            out_tables: table("tbl")
        }
    );
}

#[test]
fn insert_select_table() {
    assert_eq!(
        test_sql("INSERT INTO TEST SELECT * FROM TEMP",),
        SqlMeta {
            in_tables: table("TEMP"),
            out_tables: table("TEST")
        }
    );
}

#[test]
fn insert_nested_select() {
    assert_eq!(test_sql("
                INSERT INTO popular_orders_day_of_week (order_day_of_week, order_placed_on,orders_placed)
                SELECT EXTRACT(ISODOW FROM order_placed_on) AS order_day_of_week,
                    order_placed_on,
                    COUNT(*) AS orders_placed
                FROM top_delivery_times
                GROUP BY order_placed_on;
            ",
    ), SqlMeta {
        in_tables: table("top_delivery_times"),
        out_tables: table("popular_orders_day_of_week")
    })
}

#[test]
fn insert_overwrite_table() {
    assert_eq!(
        test_sql(
            "\
        INSERT OVERWRITE TABLE schema.daily_play_sessions_v2
        PARTITION (ds = '2022-03-30')
        SELECT
            platform_id,
            universe_id,
            pii_userid,
            NULL as session_id,
            NULL as session_start_ts,
            COUNT(1) AS session_cnt,
            SUM(
                UNIX_TIMESTAMP(stopped) - UNIX_TIMESTAMP(joined)
            ) AS time_spent_sec
        FROM schema.fct_play_sessions_merged
        WHERE ds = '2022-03-30'
            AND UNIX_TIMESTAMP(stopped) - UNIX_TIMESTAMP(joined) BETWEEN 0 AND 28800
        GROUP BY
            platform_id,
            universe_id,
            pii_userid
    "
        ),
        SqlMeta {
            in_tables: table("schema.fct_play_sessions_merged"),
            out_tables: table("schema.daily_play_sessions_v2")
        }
    )
}

#[test]
fn insert_overwrite_subqueries() {
    assert_eq!(
        test_sql(
            "
        INSERT OVERWRITE TABLE mytable
        PARTITION (ds = '2022-03-31')
        SELECT
            *
        FROM
        (SELECT * FROM table2) a"
        ),
        SqlMeta {
            in_tables: table("table2"),
            out_tables: table("mytable")
        }
    )
}

#[test]
fn insert_overwrite_multiple_subqueries() {
    assert_eq!(
        test_sql(
            "
        INSERT OVERWRITE TABLE mytable
        PARTITION (ds = '2022-03-31')
        SELECT
            *
        FROM
        (SELECT * FROM table2
         UNION
         SELECT * FROM table3
         UNION ALL
         SELECT * FROM table4) a
         "
        ),
        SqlMeta {
            in_tables: tables(vec!["table2", "table3", "table4"]),
            out_tables: table("mytable")
        }
    )
}
