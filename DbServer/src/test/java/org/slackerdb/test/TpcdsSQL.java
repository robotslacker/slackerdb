package org.slackerdb.test;

import java.util.HashMap;
import java.util.Map;

public class TpcdsSQL {

    static Map<String, String> loadTPCDSSQLMap()
    {
        Map<String, String> tpcdsSQLMap = new HashMap<>();

        tpcdsSQLMap.put(
                "TPCDS01",
                " WITH customer_total_return AS\n" +
                        "   (SELECT sr_customer_sk AS ctr_customer_sk, sr_store_sk AS ctr_store_sk,\n" +
                        "           sum(sr_return_amt) AS ctr_total_return\n" +
                        "    FROM store_returns, date_dim\n" +
                        "    WHERE sr_returned_date_sk = d_date_sk AND d_year = 2000\n" +
                        "    GROUP BY sr_customer_sk, sr_store_sk)\n" +
                        " SELECT c_customer_id\n" +
                        "   FROM customer_total_return ctr1, store, customer\n" +
                        "   WHERE ctr1.ctr_total_return >\n" +
                        "    (SELECT avg(ctr_total_return)*1.2\n" +
                        "      FROM customer_total_return ctr2\n" +
                        "       WHERE ctr1.ctr_store_sk = ctr2.ctr_store_sk)\n" +
                        "   AND s_store_sk = ctr1.ctr_store_sk\n" +
                        "   AND s_state = 'TN'\n" +
                        "   AND ctr1.ctr_customer_sk = c_customer_sk\n" +
                        "   ORDER BY c_customer_id LIMIT 100\n" +
                        "            "
        );
        tpcdsSQLMap.put(
                "TPCDS02",
                " WITH wscs as\n" +
                        " (SELECT sold_date_sk, sales_price\n" +
                        "  FROM (SELECT ws_sold_date_sk sold_date_sk, ws_ext_sales_price sales_price\n" +
                        "        FROM web_sales\n" +
                        "        UNION ALL\n" +
                        "       SELECT cs_sold_date_sk sold_date_sk, cs_ext_sales_price sales_price\n" +
                        "        FROM catalog_sales)),\n" +
                        " wswscs AS\n" +
                        " (SELECT d_week_seq,\n" +
                        "        sum(case when (d_day_name='Sunday') then sales_price else null end) sun_sales,\n" +
                        "        sum(case when (d_day_name='Monday') then sales_price else null end) mon_sales,\n" +
                        "        sum(case when (d_day_name='Tuesday') then sales_price else  null end) tue_sales,\n" +
                        "        sum(case when (d_day_name='Wednesday') then sales_price else null end) wed_sales,\n" +
                        "        sum(case when (d_day_name='Thursday') then sales_price else null end) thu_sales,\n" +
                        "        sum(case when (d_day_name='Friday') then sales_price else null end) fri_sales,\n" +
                        "        sum(case when (d_day_name='Saturday') then sales_price else null end) sat_sales\n" +
                        " FROM wscs, date_dim\n" +
                        " WHERE d_date_sk = sold_date_sk\n" +
                        " GROUP BY d_week_seq)\n" +
                        " SELECT d_week_seq1\n" +
                        "       ,round(sun_sales1/sun_sales2,2)\tsun_rate\n" +
                        "       ,round(mon_sales1/mon_sales2,2)\tmon_rate\n" +
                        "       ,round(tue_sales1/tue_sales2,2)\ttue_rate\n" +
                        "       ,round(wed_sales1/wed_sales2,2)\twed_rate\n" +
                        "       ,round(thu_sales1/thu_sales2,2)\tthu_rate\n" +
                        "       ,round(fri_sales1/fri_sales2,2)\tfri_rate\n" +
                        "       ,round(sat_sales1/sat_sales2,2)\tsat_rate\n" +
                        " FROM\n" +
                        " (SELECT wswscs.d_week_seq d_week_seq1\n" +
                        "        ,sun_sales sun_sales1\n" +
                        "        ,mon_sales mon_sales1\n" +
                        "        ,tue_sales tue_sales1\n" +
                        "        ,wed_sales wed_sales1\n" +
                        "        ,thu_sales thu_sales1\n" +
                        "        ,fri_sales fri_sales1\n" +
                        "        ,sat_sales sat_sales1\n" +
                        "  FROM wswscs,date_dim\n" +
                        "  WHERE date_dim.d_week_seq = wswscs.d_week_seq AND d_year = 2001) y,\n" +
                        " (SELECT wswscs.d_week_seq d_week_seq2\n" +
                        "        ,sun_sales sun_sales2\n" +
                        "        ,mon_sales mon_sales2\n" +
                        "        ,tue_sales tue_sales2\n" +
                        "        ,wed_sales wed_sales2\n" +
                        "        ,thu_sales thu_sales2\n" +
                        "        ,fri_sales fri_sales2\n" +
                        "        ,sat_sales sat_sales2\n" +
                        "  FROM wswscs, date_dim\n" +
                        "  WHERE date_dim.d_week_seq = wswscs.d_week_seq AND d_year = 2001 + 1) z\n" +
                        " WHERE d_week_seq1=d_week_seq2-53\n" +
                        " ORDER BY d_week_seq1\n" +
                        "            \n"
        );
        tpcdsSQLMap.put(
                "TPCDS03",
                " SELECT dt.d_year, item.i_brand_id brand_id, item.i_brand brand,SUM(ss_ext_sales_price) sum_agg\n" +
                        " FROM  date_dim dt, store_sales, item\n" +
                        " WHERE dt.d_date_sk = store_sales.ss_sold_date_sk\n" +
                        "   AND store_sales.ss_item_sk = item.i_item_sk\n" +
                        "   AND item.i_manufact_id = 128\n" +
                        "   AND dt.d_moy=11\n" +
                        " GROUP BY dt.d_year, item.i_brand, item.i_brand_id\n" +
                        " ORDER BY dt.d_year, sum_agg desc, brand_id\n" +
                        " LIMIT 100"
        );
        tpcdsSQLMap.put(
                "TPCDS04",
                "WITH year_total AS (\n" +
                        " SELECT c_customer_id customer_id,\n" +
                        "        c_first_name customer_first_name,\n" +
                        "        c_last_name customer_last_name,\n" +
                        "        c_preferred_cust_flag customer_preferred_cust_flag,\n" +
                        "        c_birth_country customer_birth_country,\n" +
                        "        c_login customer_login,\n" +
                        "        c_email_address customer_email_address,\n" +
                        "        d_year dyear,\n" +
                        "        sum(((ss_ext_list_price-ss_ext_wholesale_cost-ss_ext_discount_amt)+ss_ext_sales_price)/2) year_total,\n" +
                        "        's' sale_type\n" +
                        " FROM customer, store_sales, date_dim\n" +
                        " WHERE c_customer_sk = ss_customer_sk AND ss_sold_date_sk = d_date_sk\n" +
                        " GROUP BY c_customer_id,\n" +
                        "          c_first_name,\n" +
                        "          c_last_name,\n" +
                        "          c_preferred_cust_flag,\n" +
                        "          c_birth_country,\n" +
                        "          c_login,\n" +
                        "          c_email_address,\n" +
                        "          d_year\n" +
                        " UNION ALL\n" +
                        " SELECT c_customer_id customer_id,\n" +
                        "        c_first_name customer_first_name,\n" +
                        "        c_last_name customer_last_name,\n" +
                        "        c_preferred_cust_flag customer_preferred_cust_flag,\n" +
                        "        c_birth_country customer_birth_country,\n" +
                        "        c_login customer_login,\n" +
                        "        c_email_address customer_email_address,\n" +
                        "        d_year dyear,\n" +
                        "        sum((((cs_ext_list_price-cs_ext_wholesale_cost-cs_ext_discount_amt)+cs_ext_sales_price)/2) ) year_total,\n" +
                        "        'c' sale_type\n" +
                        " FROM customer, catalog_sales, date_dim\n" +
                        " WHERE c_customer_sk = cs_bill_customer_sk AND cs_sold_date_sk = d_date_sk\n" +
                        " GROUP BY c_customer_id,\n" +
                        "          c_first_name,\n" +
                        "          c_last_name,\n" +
                        "          c_preferred_cust_flag,\n" +
                        "          c_birth_country,\n" +
                        "          c_login,\n" +
                        "          c_email_address,\n" +
                        "          d_year\n" +
                        " UNION ALL\n" +
                        " SELECT c_customer_id customer_id\n" +
                        "       ,c_first_name customer_first_name\n" +
                        "       ,c_last_name customer_last_name\n" +
                        "       ,c_preferred_cust_flag customer_preferred_cust_flag\n" +
                        "       ,c_birth_country customer_birth_country\n" +
                        "       ,c_login customer_login\n" +
                        "       ,c_email_address customer_email_address\n" +
                        "       ,d_year dyear\n" +
                        "       ,sum((((ws_ext_list_price-ws_ext_wholesale_cost-ws_ext_discount_amt)+ws_ext_sales_price)/2) ) year_total\n" +
                        "       ,'w' sale_type\n" +
                        " FROM customer, web_sales, date_dim\n" +
                        " WHERE c_customer_sk = ws_bill_customer_sk AND ws_sold_date_sk = d_date_sk\n" +
                        " GROUP BY c_customer_id,\n" +
                        "          c_first_name,\n" +
                        "          c_last_name,\n" +
                        "          c_preferred_cust_flag,\n" +
                        "          c_birth_country,\n" +
                        "          c_login,\n" +
                        "          c_email_address,\n" +
                        "          d_year)\n" +
                        " SELECT\n" +
                        "   t_s_secyear.customer_id,\n" +
                        "   t_s_secyear.customer_first_name,\n" +
                        "   t_s_secyear.customer_last_name,\n" +
                        "   t_s_secyear.customer_preferred_cust_flag\n" +
                        " FROM year_total t_s_firstyear, year_total t_s_secyear, year_total t_c_firstyear,\n" +
                        "      year_total t_c_secyear, year_total t_w_firstyear, year_total t_w_secyear\n" +
                        " WHERE t_s_secyear.customer_id = t_s_firstyear.customer_id\n" +
                        "   and t_s_firstyear.customer_id = t_c_secyear.customer_id\n" +
                        "   and t_s_firstyear.customer_id = t_c_firstyear.customer_id\n" +
                        "   and t_s_firstyear.customer_id = t_w_firstyear.customer_id\n" +
                        "   and t_s_firstyear.customer_id = t_w_secyear.customer_id\n" +
                        "   and t_s_firstyear.sale_type = 's'\n" +
                        "   and t_c_firstyear.sale_type = 'c'\n" +
                        "   and t_w_firstyear.sale_type = 'w'\n" +
                        "   and t_s_secyear.sale_type = 's'\n" +
                        "   and t_c_secyear.sale_type = 'c'\n" +
                        "   and t_w_secyear.sale_type = 'w'\n" +
                        "   and t_s_firstyear.dyear = 2001\n" +
                        "   and t_s_secyear.dyear = 2001+1\n" +
                        "   and t_c_firstyear.dyear = 2001\n" +
                        "   and t_c_secyear.dyear = 2001+1\n" +
                        "   and t_w_firstyear.dyear = 2001\n" +
                        "   and t_w_secyear.dyear = 2001+1\n" +
                        "   and t_s_firstyear.year_total > 0\n" +
                        "   and t_c_firstyear.year_total > 0\n" +
                        "   and t_w_firstyear.year_total > 0\n" +
                        "   and case when t_c_firstyear.year_total > 0 then t_c_secyear.year_total / t_c_firstyear.year_total else null end\n" +
                        "           > case when t_s_firstyear.year_total > 0 then t_s_secyear.year_total / t_s_firstyear.year_total else null end\n" +
                        "   and case when t_c_firstyear.year_total > 0 then t_c_secyear.year_total / t_c_firstyear.year_total else null end\n" +
                        "           > case when t_w_firstyear.year_total > 0 then t_w_secyear.year_total / t_w_firstyear.year_total else null end\n" +
                        " ORDER BY\n" +
                        "   t_s_secyear.customer_id,\n" +
                        "   t_s_secyear.customer_first_name,\n" +
                        "   t_s_secyear.customer_last_name,\n" +
                        "   t_s_secyear.customer_preferred_cust_flag\n" +
                        " LIMIT 100"
        );
        tpcdsSQLMap.put(
                "TPCDS05",
                "WITH ssr AS\n" +
                        "  (SELECT s_store_id,\n" +
                        "          sum(sales_price) as sales,\n" +
                        "          sum(profit) as profit,\n" +
                        "          sum(return_amt) as returns,\n" +
                        "          sum(net_loss) as profit_loss\n" +
                        "  FROM\n" +
                        "    (SELECT ss_store_sk as store_sk,\n" +
                        "            ss_sold_date_sk  as date_sk,\n" +
                        "            ss_ext_sales_price as sales_price,\n" +
                        "            ss_net_profit as profit,\n" +
                        "            cast(0 as decimal(7,2)) as return_amt,\n" +
                        "            cast(0 as decimal(7,2)) as net_loss\n" +
                        "    FROM store_sales\n" +
                        "    UNION ALL\n" +
                        "    SELECT sr_store_sk as store_sk,\n" +
                        "           sr_returned_date_sk as date_sk,\n" +
                        "           cast(0 as decimal(7,2)) as sales_price,\n" +
                        "           cast(0 as decimal(7,2)) as profit,\n" +
                        "           sr_return_amt as return_amt,\n" +
                        "           sr_net_loss as net_loss\n" +
                        "    FROM store_returns)\n" +
                        "    salesreturns, date_dim, store\n" +
                        "  WHERE date_sk = d_date_sk\n" +
                        "       and d_date between cast('2000-08-23' as date)\n" +
                        "                  and ((cast('2000-08-23' as date) + interval '14' day))\n" +
                        "       and store_sk = s_store_sk\n" +
                        " GROUP BY s_store_id),\n" +
                        " csr AS\n" +
                        " (SELECT cp_catalog_page_id,\n" +
                        "         sum(sales_price) as sales,\n" +
                        "         sum(profit) as profit,\n" +
                        "         sum(return_amt) as returns,\n" +
                        "         sum(net_loss) as profit_loss\n" +
                        " FROM\n" +
                        "   (SELECT cs_catalog_page_sk as page_sk,\n" +
                        "           cs_sold_date_sk  as date_sk,\n" +
                        "           cs_ext_sales_price as sales_price,\n" +
                        "           cs_net_profit as profit,\n" +
                        "           cast(0 as decimal(7,2)) as return_amt,\n" +
                        "           cast(0 as decimal(7,2)) as net_loss\n" +
                        "    FROM catalog_sales\n" +
                        "    UNION ALL\n" +
                        "    SELECT cr_catalog_page_sk as page_sk,\n" +
                        "           cr_returned_date_sk as date_sk,\n" +
                        "           cast(0 as decimal(7,2)) as sales_price,\n" +
                        "           cast(0 as decimal(7,2)) as profit,\n" +
                        "           cr_return_amount as return_amt,\n" +
                        "           cr_net_loss as net_loss\n" +
                        "    from catalog_returns\n" +
                        "   ) salesreturns, date_dim, catalog_page\n" +
                        " WHERE date_sk = d_date_sk\n" +
                        "       and d_date between cast('2000-08-23' as date)\n" +
                        "                  and ((cast('2000-08-23' as date) + interval '14' day))\n" +
                        "       and page_sk = cp_catalog_page_sk\n" +
                        " GROUP BY cp_catalog_page_id)\n" +
                        " ,\n" +
                        " wsr AS\n" +
                        " (SELECT web_site_id,\n" +
                        "         sum(sales_price) as sales,\n" +
                        "         sum(profit) as profit,\n" +
                        "         sum(return_amt) as returns,\n" +
                        "         sum(net_loss) as profit_loss\n" +
                        " from\n" +
                        "  (select  ws_web_site_sk as wsr_web_site_sk,\n" +
                        "            ws_sold_date_sk  as date_sk,\n" +
                        "            ws_ext_sales_price as sales_price,\n" +
                        "            ws_net_profit as profit,\n" +
                        "            cast(0 as decimal(7,2)) as return_amt,\n" +
                        "            cast(0 as decimal(7,2)) as net_loss\n" +
                        "    from web_sales\n" +
                        "    union all\n" +
                        "    select ws_web_site_sk as wsr_web_site_sk,\n" +
                        "           wr_returned_date_sk as date_sk,\n" +
                        "           cast(0 as decimal(7,2)) as sales_price,\n" +
                        "           cast(0 as decimal(7,2)) as profit,\n" +
                        "           wr_return_amt as return_amt,\n" +
                        "           wr_net_loss as net_loss\n" +
                        "    FROM web_returns LEFT  OUTER JOIN web_sales on\n" +
                        "         ( wr_item_sk = ws_item_sk\n" +
                        "           and wr_order_number = ws_order_number)\n" +
                        "   ) salesreturns, date_dim, web_site\n" +
                        " WHERE date_sk = d_date_sk\n" +
                        "       and d_date between cast('2000-08-23' as date)\n" +
                        "                  and ((cast('2000-08-23' as date) + interval '14' day))\n" +
                        "       and wsr_web_site_sk = web_site_sk\n" +
                        " GROUP BY web_site_id)\n" +
                        " SELECT channel,\n" +
                        "        id,\n" +
                        "        sum(sales) as sales,\n" +
                        "        sum(returns) as returns,\n" +
                        "        sum(profit) as profit\n" +
                        " from\n" +
                        " (select 'store channel' as channel,\n" +
                        "         concat('store', s_store_id) as id,\n" +
                        "         sales,\n" +
                        "         returns,\n" +
                        "        (profit - profit_loss) as profit\n" +
                        " FROM ssr\n" +
                        " UNION ALL\n" +
                        " select 'catalog channel' as channel,\n" +
                        "        concat('catalog_page', cp_catalog_page_id) as id,\n" +
                        "        sales,\n" +
                        "        returns,\n" +
                        "        (profit - profit_loss) as profit\n" +
                        " FROM  csr\n" +
                        " UNION ALL\n" +
                        " SELECT 'web channel' as channel,\n" +
                        "        concat('web_site', web_site_id) as id,\n" +
                        "        sales,\n" +
                        "        returns,\n" +
                        "        (profit - profit_loss) as profit\n" +
                        " FROM wsr\n" +
                        " ) x\n" +
                        " GROUP BY ROLLUP (channel, id)\n" +
                        " ORDER BY channel, id\n" +
                        " LIMIT 100"
        );
        tpcdsSQLMap.put(
                "TPCDS06",
                "SELECT state, cnt FROM (\n" +
                        " SELECT a.ca_state state, count(*) cnt\n" +
                        " FROM\n" +
                        "    customer_address a, customer c, store_sales s, date_dim d, item i\n" +
                        " WHERE a.ca_address_sk = c.c_current_addr_sk\n" +
                        " \tAND c.c_customer_sk = s.ss_customer_sk\n" +
                        " \tAND s.ss_sold_date_sk = d.d_date_sk\n" +
                        " \tAND s.ss_item_sk = i.i_item_sk\n" +
                        " \tAND d.d_month_seq =\n" +
                        " \t     (SELECT distinct (d_month_seq) FROM date_dim \n" +
                        "        WHERE d_year = 2001 AND d_moy = 1)\n" +
                        " \tAND i.i_current_price > 1.2 *\n" +
                        "             (SELECT avg(j.i_current_price) FROM item j\n" +
                        " \t            WHERE j.i_category = i.i_category)\n" +
                        " GROUP BY a.ca_state\n" +
                        ") \n" +
                        "WHERE cnt >= 10\n" +
                        "ORDER BY cnt LIMIT 100"
        );
        tpcdsSQLMap.put(
                "TPCDS07",
                " SELECT i_item_id,\n" +
                        "        avg(ss_quantity) agg1,\n" +
                        "        avg(ss_list_price) agg2,\n" +
                        "        avg(ss_coupon_amt) agg3,\n" +
                        "        avg(ss_sales_price) agg4\n" +
                        " FROM store_sales, customer_demographics, date_dim, item, promotion\n" +
                        " WHERE ss_sold_date_sk = d_date_sk AND\n" +
                        "       ss_item_sk = i_item_sk AND\n" +
                        "       ss_cdemo_sk = cd_demo_sk AND\n" +
                        "       ss_promo_sk = p_promo_sk AND\n" +
                        "       cd_gender = 'M' AND\n" +
                        "       cd_marital_status = 'S' AND\n" +
                        "       cd_education_status = 'College' AND\n" +
                        "       (p_channel_email = 'N' or p_channel_event = 'N') AND\n" +
                        "       d_year = 2000\n" +
                        " GROUP BY i_item_id\n" +
                        " ORDER BY i_item_id LIMIT 100"
        );
        tpcdsSQLMap.put(
                "TPCDS08",
                "select s_store_name, sum(ss_net_profit)\n" +
                        " from store_sales, date_dim, store,\n" +
                        "     (SELECT ca_zip\n" +
                        "       from (\n" +
                        "       (SELECT substr(ca_zip,1,5) ca_zip FROM customer_address\n" +
                        "          WHERE substr(ca_zip,1,5) IN (\n" +
                        "               '24128','76232','65084','87816','83926','77556','20548',\n" +
                        "               '26231','43848','15126','91137','61265','98294','25782',\n" +
                        "               '17920','18426','98235','40081','84093','28577','55565',\n" +
                        "               '17183','54601','67897','22752','86284','18376','38607',\n" +
                        "               '45200','21756','29741','96765','23932','89360','29839',\n" +
                        "               '25989','28898','91068','72550','10390','18845','47770',\n" +
                        "               '82636','41367','76638','86198','81312','37126','39192',\n" +
                        "               '88424','72175','81426','53672','10445','42666','66864',\n" +
                        "               '66708','41248','48583','82276','18842','78890','49448',\n" +
                        "               '14089','38122','34425','79077','19849','43285','39861',\n" +
                        "               '66162','77610','13695','99543','83444','83041','12305',\n" +
                        "               '57665','68341','25003','57834','62878','49130','81096',\n" +
                        "               '18840','27700','23470','50412','21195','16021','76107',\n" +
                        "               '71954','68309','18119','98359','64544','10336','86379',\n" +
                        "               '27068','39736','98569','28915','24206','56529','57647',\n" +
                        "               '54917','42961','91110','63981','14922','36420','23006',\n" +
                        "               '67467','32754','30903','20260','31671','51798','72325',\n" +
                        "               '85816','68621','13955','36446','41766','68806','16725',\n" +
                        "               '15146','22744','35850','88086','51649','18270','52867',\n" +
                        "               '39972','96976','63792','11376','94898','13595','10516',\n" +
                        "               '90225','58943','39371','94945','28587','96576','57855',\n" +
                        "               '28488','26105','83933','25858','34322','44438','73171',\n" +
                        "               '30122','34102','22685','71256','78451','54364','13354',\n" +
                        "               '45375','40558','56458','28286','45266','47305','69399',\n" +
                        "               '83921','26233','11101','15371','69913','35942','15882',\n" +
                        "               '25631','24610','44165','99076','33786','70738','26653',\n" +
                        "               '14328','72305','62496','22152','10144','64147','48425',\n" +
                        "               '14663','21076','18799','30450','63089','81019','68893',\n" +
                        "               '24996','51200','51211','45692','92712','70466','79994',\n" +
                        "               '22437','25280','38935','71791','73134','56571','14060',\n" +
                        "               '19505','72425','56575','74351','68786','51650','20004',\n" +
                        "               '18383','76614','11634','18906','15765','41368','73241',\n" +
                        "               '76698','78567','97189','28545','76231','75691','22246',\n" +
                        "               '51061','90578','56691','68014','51103','94167','57047',\n" +
                        "               '14867','73520','15734','63435','25733','35474','24676',\n" +
                        "               '94627','53535','17879','15559','53268','59166','11928',\n" +
                        "               '59402','33282','45721','43933','68101','33515','36634',\n" +
                        "               '71286','19736','58058','55253','67473','41918','19515',\n" +
                        "               '36495','19430','22351','77191','91393','49156','50298',\n" +
                        "               '87501','18652','53179','18767','63193','23968','65164',\n" +
                        "               '68880','21286','72823','58470','67301','13394','31016',\n" +
                        "               '70372','67030','40604','24317','45748','39127','26065',\n" +
                        "               '77721','31029','31880','60576','24671','45549','13376',\n" +
                        "               '50016','33123','19769','22927','97789','46081','72151',\n" +
                        "               '15723','46136','51949','68100','96888','64528','14171',\n" +
                        "               '79777','28709','11489','25103','32213','78668','22245',\n" +
                        "               '15798','27156','37930','62971','21337','51622','67853',\n" +
                        "               '10567','38415','15455','58263','42029','60279','37125',\n" +
                        "               '56240','88190','50308','26859','64457','89091','82136',\n" +
                        "               '62377','36233','63837','58078','17043','30010','60099',\n" +
                        "               '28810','98025','29178','87343','73273','30469','64034',\n" +
                        "               '39516','86057','21309','90257','67875','40162','11356',\n" +
                        "               '73650','61810','72013','30431','22461','19512','13375',\n" +
                        "               '55307','30625','83849','68908','26689','96451','38193',\n" +
                        "               '46820','88885','84935','69035','83144','47537','56616',\n" +
                        "               '94983','48033','69952','25486','61547','27385','61860',\n" +
                        "               '58048','56910','16807','17871','35258','31387','35458',\n" +
                        "               '35576'))\n" +
                        "       INTERSECT\n" +
                        "       (select ca_zip\n" +
                        "          FROM\n" +
                        "            (SELECT substr(ca_zip,1,5) ca_zip,count(*) cnt\n" +
                        "              FROM customer_address, customer\n" +
                        "              WHERE ca_address_sk = c_current_addr_sk and\n" +
                        "                    c_preferred_cust_flag='Y'\n" +
                        "              group by ca_zip\n" +
                        "              having count(*) > 10) A1)\n" +
                        "         ) A2\n" +
                        "      ) V1\n" +
                        " where ss_store_sk = s_store_sk\n" +
                        "  and ss_sold_date_sk = d_date_sk\n" +
                        "  and d_qoy = 2 and d_year = 1998\n" +
                        "  and (substr(s_zip,1,2) = substr(V1.ca_zip,1,2))\n" +
                        " group by s_store_name\n" +
                        " order by s_store_name LIMIT 100"
        );
        tpcdsSQLMap.put(
                "TPCDS09",
                "select case when (select count(*) from store_sales\n" +
                        "                  where ss_quantity between 1 and 20) > 74129\n" +
                        "            then (select avg(ss_ext_discount_amt) from store_sales\n" +
                        "                  where ss_quantity between 1 and 20)\n" +
                        "            else (select avg(ss_net_paid) from store_sales\n" +
                        "                  where ss_quantity between 1 and 20) end bucket1 ,\n" +
                        "       case when (select count(*) from store_sales\n" +
                        "                  where ss_quantity between 21 and 40) > 122840\n" +
                        "            then (select avg(ss_ext_discount_amt) from store_sales\n" +
                        "                  where ss_quantity between 21 and 40)\n" +
                        "            else (select avg(ss_net_paid) from store_sales\n" +
                        "                  where ss_quantity between 21 and 40) end bucket2,\n" +
                        "       case when (select count(*) from store_sales\n" +
                        "                  where ss_quantity between 41 and 60) > 56580\n" +
                        "            then (select avg(ss_ext_discount_amt) from store_sales\n" +
                        "                  where ss_quantity between 41 and 60)\n" +
                        "            else (select avg(ss_net_paid) from store_sales\n" +
                        "                  where ss_quantity between 41 and 60) end bucket3,\n" +
                        "       case when (select count(*) from store_sales\n" +
                        "                  where ss_quantity between 61 and 80) > 10097\n" +
                        "            then (select avg(ss_ext_discount_amt) from store_sales\n" +
                        "                  where ss_quantity between 61 and 80)\n" +
                        "            else (select avg(ss_net_paid) from store_sales\n" +
                        "                  where ss_quantity between 61 and 80) end bucket4,\n" +
                        "       case when (select count(*) from store_sales\n" +
                        "                  where ss_quantity between 81 and 100) > 165306\n" +
                        "            then (select avg(ss_ext_discount_amt) from store_sales\n" +
                        "                  where ss_quantity between 81 and 100)\n" +
                        "            else (select avg(ss_net_paid) from store_sales\n" +
                        "                  where ss_quantity between 81 and 100) end bucket5\n" +
                        "from reason\n" +
                        "where r_reason_sk = 1"
        );
        tpcdsSQLMap.put(
                "TPCDS10",
                " select\n" +
                        "  cd_gender, cd_marital_status, cd_education_status, count(*) cnt1,\n" +
                        "  cd_purchase_estimate, count(*) cnt2, cd_credit_rating, count(*) cnt3,\n" +
                        "  cd_dep_count, count(*) cnt4, cd_dep_employed_count,  count(*) cnt5,\n" +
                        "  cd_dep_college_count, count(*) cnt6\n" +
                        " from\n" +
                        "  customer c, customer_address ca, customer_demographics\n" +
                        " where\n" +
                        "  c.c_current_addr_sk = ca.ca_address_sk and\n" +
                        "  ca_county in ('Rush County','Toole County','Jefferson County',\n" +
                        "                'Dona Ana County','La Porte County') and\n" +
                        "  cd_demo_sk = c.c_current_cdemo_sk AND\n" +
                        "  exists (select * from store_sales, date_dim\n" +
                        "          where c.c_customer_sk = ss_customer_sk AND\n" +
                        "                ss_sold_date_sk = d_date_sk AND\n" +
                        "                d_year = 2002 AND\n" +
                        "                d_moy between 1 AND 1+3) AND\n" +
                        "   (exists (select * from web_sales, date_dim\n" +
                        "            where c.c_customer_sk = ws_bill_customer_sk AND\n" +
                        "                  ws_sold_date_sk = d_date_sk AND\n" +
                        "                  d_year = 2002 AND\n" +
                        "                  d_moy between 1 AND 1+3) or\n" +
                        "    exists (select * from catalog_sales, date_dim\n" +
                        "            where c.c_customer_sk = cs_ship_customer_sk AND\n" +
                        "                  cs_sold_date_sk = d_date_sk AND\n" +
                        "                  d_year = 2002 AND\n" +
                        "                  d_moy between 1 AND 1+3))\n" +
                        " group by cd_gender,\n" +
                        "          cd_marital_status,\n" +
                        "          cd_education_status,\n" +
                        "          cd_purchase_estimate,\n" +
                        "          cd_credit_rating,\n" +
                        "          cd_dep_count,\n" +
                        "          cd_dep_employed_count,\n" +
                        "          cd_dep_college_count\n" +
                        " order by cd_gender,\n" +
                        "          cd_marital_status,\n" +
                        "          cd_education_status,\n" +
                        "          cd_purchase_estimate,\n" +
                        "          cd_credit_rating,\n" +
                        "          cd_dep_count,\n" +
                        "          cd_dep_employed_count,\n" +
                        "          cd_dep_college_count\n" +
                        "LIMIT 100"
        );
        tpcdsSQLMap.put(
                "TPCDS11",
                "with year_total as (\n" +
                        " select c_customer_id customer_id\n" +
                        "       ,c_first_name customer_first_name\n" +
                        "       ,c_last_name customer_last_name\n" +
                        "       ,c_preferred_cust_flag customer_preferred_cust_flag\n" +
                        "       ,c_birth_country customer_birth_country\n" +
                        "       ,c_login customer_login\n" +
                        "       ,c_email_address customer_email_address\n" +
                        "       ,d_year dyear\n" +
                        "       ,sum(ss_ext_list_price-ss_ext_discount_amt) year_total\n" +
                        "       ,'s' sale_type\n" +
                        " from customer, store_sales, date_dim\n" +
                        " where c_customer_sk = ss_customer_sk\n" +
                        "   and ss_sold_date_sk = d_date_sk\n" +
                        " group by c_customer_id\n" +
                        "         ,c_first_name\n" +
                        "         ,c_last_name\n" +
                        "         ,c_preferred_cust_flag\n" +
                        "         ,c_birth_country\n" +
                        "         ,c_login\n" +
                        "         ,c_email_address\n" +
                        "         ,d_year\n" +
                        " union all\n" +
                        " select c_customer_id customer_id\n" +
                        "       ,c_first_name customer_first_name\n" +
                        "       ,c_last_name customer_last_name\n" +
                        "       ,c_preferred_cust_flag customer_preferred_cust_flag\n" +
                        "       ,c_birth_country customer_birth_country\n" +
                        "       ,c_login customer_login\n" +
                        "       ,c_email_address customer_email_address\n" +
                        "       ,d_year dyear\n" +
                        "       ,sum(ws_ext_list_price-ws_ext_discount_amt) year_total\n" +
                        "       ,'w' sale_type\n" +
                        " from customer, web_sales, date_dim\n" +
                        " where c_customer_sk = ws_bill_customer_sk\n" +
                        "   and ws_sold_date_sk = d_date_sk\n" +
                        " group by\n" +
                        "    c_customer_id, c_first_name, c_last_name, c_preferred_cust_flag, c_birth_country,\n" +
                        "    c_login, c_email_address, d_year)\n" +
                        " select\n" +
                        "    t_s_secyear.customer_id\n" +
                        "   ,t_s_secyear.customer_first_name\n" +
                        "   ,t_s_secyear.customer_last_name\n" +
                        "   ,t_s_secyear.customer_preferred_cust_flag\n" +
                        " from year_total t_s_firstyear\n" +
                        "     ,year_total t_s_secyear\n" +
                        "     ,year_total t_w_firstyear\n" +
                        "     ,year_total t_w_secyear\n" +
                        " where t_s_secyear.customer_id = t_s_firstyear.customer_id\n" +
                        "         and t_s_firstyear.customer_id = t_w_secyear.customer_id\n" +
                        "         and t_s_firstyear.customer_id = t_w_firstyear.customer_id\n" +
                        "         and t_s_firstyear.sale_type = 's'\n" +
                        "         and t_w_firstyear.sale_type = 'w'\n" +
                        "         and t_s_secyear.sale_type = 's'\n" +
                        "         and t_w_secyear.sale_type = 'w'\n" +
                        "         and t_s_firstyear.dyear = 2001\n" +
                        "         and t_s_secyear.dyear = 2001+1\n" +
                        "         and t_w_firstyear.dyear = 2001\n" +
                        "         and t_w_secyear.dyear = 2001+1\n" +
                        "         and t_s_firstyear.year_total > 0\n" +
                        "         and t_w_firstyear.year_total > 0\n" +
                        "         and case when t_w_firstyear.year_total > 0 then t_w_secyear.year_total / t_w_firstyear.year_total else 0.0 end\n" +
                        "             > case when t_s_firstyear.year_total > 0 then t_s_secyear.year_total / t_s_firstyear.year_total else 0.0 end\n" +
                        " order by \n" +
                        "         t_s_secyear.customer_id\n" +
                        "         ,t_s_secyear.customer_first_name\n" +
                        "         ,t_s_secyear.customer_last_name\n" +
                        "         ,\n" +
                        "t_s_secyear.customer_preferred_cust_flag\n" +
                        " LIMIT 100"
        );
        tpcdsSQLMap.put(
                "TPCDS12",
                " select i_item_id,\n" +
                        "  i_item_desc, i_category, i_class, i_current_price,\n" +
                        "  sum(ws_ext_sales_price) as itemrevenue,\n" +
                        "  sum(ws_ext_sales_price)*100/sum(sum(ws_ext_sales_price)) over\n" +
                        "          (partition by i_class) as revenueratio\n" +
                        " from\n" +
                        "\tweb_sales, item, date_dim\n" +
                        " where\n" +
                        "\tws_item_sk = i_item_sk\n" +
                        "  \tand i_category in ('Sports', 'Books', 'Home')\n" +
                        "  \tand ws_sold_date_sk = d_date_sk\n" +
                        "\tand d_date between cast('1999-02-22' as date)\n" +
                        "\t\t\t\tand (cast('1999-02-22' as date) + interval '30' day)\n" +
                        " group by\n" +
                        "\ti_item_id, i_item_desc, i_category, i_class, i_current_price\n" +
                        " order by\n" +
                        "\ti_category, i_class, i_item_id, i_item_desc, revenueratio\n" +
                        " LIMIT 100"
        );
        tpcdsSQLMap.put(
                "TPCDS13",
                " select avg(ss_quantity)\tavg_qu\n" +
                        "       ,avg(ss_ext_sales_price)\tavg_ext_sales\n" +
                        "       ,avg(ss_ext_wholesale_cost)\tave_ext_whole\n" +
                        "       ,sum(ss_ext_wholesale_cost)\tsum_ext_whole\n" +
                        " from store_sales\n" +
                        "     ,store\n" +
                        "     ,customer_demographics\n" +
                        "     ,household_demographics\n" +
                        "     ,customer_address\n" +
                        "     ,date_dim\n" +
                        " where s_store_sk = ss_store_sk\n" +
                        " and  ss_sold_date_sk = d_date_sk and d_year = 2001\n" +
                        " and((ss_hdemo_sk=hd_demo_sk\n" +
                        "  and cd_demo_sk = ss_cdemo_sk\n" +
                        "  and cd_marital_status = 'M'\n" +
                        "  and cd_education_status = 'Advanced Degree'\n" +
                        "  and ss_sales_price between 100.00 and 150.00\n" +
                        "  and hd_dep_count = 3\n" +
                        "     )or\n" +
                        "     (ss_hdemo_sk=hd_demo_sk\n" +
                        "  and cd_demo_sk = ss_cdemo_sk\n" +
                        "  and cd_marital_status = 'S'\n" +
                        "  and cd_education_status = 'College'\n" +
                        "  and ss_sales_price between 50.00 and 100.00\n" +
                        "  and hd_dep_count = 1\n" +
                        "     ) or\n" +
                        "     (ss_hdemo_sk=hd_demo_sk\n" +
                        "  and cd_demo_sk = ss_cdemo_sk\n" +
                        "  and cd_marital_status = 'W'\n" +
                        "  and cd_education_status = '2 yr Degree'\n" +
                        "  and ss_sales_price between 150.00 and 200.00\n" +
                        "  and hd_dep_count = 1\n" +
                        "     ))\n" +
                        " and((ss_addr_sk = ca_address_sk\n" +
                        "  and ca_country = 'United States'\n" +
                        "  and ca_state in ('TX', 'OH', 'TX')\n" +
                        "  and ss_net_profit between 100 and 200\n" +
                        "     ) or\n" +
                        "     (ss_addr_sk = ca_address_sk\n" +
                        "  and ca_country = 'United States'\n" +
                        "  and ca_state in ('OR', 'NM', 'KY')\n" +
                        "  and ss_net_profit between 150 and 300\n" +
                        "     ) or\n" +
                        "     (ss_addr_sk = ca_address_sk\n" +
                        "  and ca_country = 'United States'\n" +
                        "  and ca_state in ('VA', 'TX', 'MS')\n" +
                        "  and ss_net_profit between 50 and 250\n" +
                        "     ))"
        );
        tpcdsSQLMap.put(
                "TPCDS14A",
                "with cross_items as\n" +
                        " (select i_item_sk ss_item_sk\n" +
                        " from item,\n" +
                        "    (select iss.i_brand_id brand_id, iss.i_class_id class_id, iss.i_category_id category_id\n" +
                        "     from store_sales, item iss, date_dim d1\n" +
                        "     where ss_item_sk = iss.i_item_sk\n" +
                        "                    and ss_sold_date_sk = d1.d_date_sk\n" +
                        "       and d1.d_year between 1999 AND 1999 + 2\n" +
                        "   intersect\n" +
                        "     select ics.i_brand_id, ics.i_class_id, ics.i_category_id\n" +
                        "     from catalog_sales, item ics, date_dim d2\n" +
                        "     where cs_item_sk = ics.i_item_sk\n" +
                        "       and cs_sold_date_sk = d2.d_date_sk\n" +
                        "       and d2.d_year between 1999 AND 1999 + 2\n" +
                        "   intersect\n" +
                        "     select iws.i_brand_id, iws.i_class_id, iws.i_category_id\n" +
                        "     from web_sales, item iws, date_dim d3\n" +
                        "     where ws_item_sk = iws.i_item_sk\n" +
                        "       and ws_sold_date_sk = d3.d_date_sk\n" +
                        "       and d3.d_year between 1999 AND 1999 + 2) x\n" +
                        " where i_brand_id = brand_id\n" +
                        "   and i_class_id = class_id\n" +
                        "   and i_category_id = category_id\n" +
                        "),\n" +
                        " avg_sales as\n" +
                        " (select avg(quantity*list_price) average_sales\n" +
                        "  from (\n" +
                        "     select ss_quantity quantity, ss_list_price list_price\n" +
                        "     from store_sales, date_dim\n" +
                        "     where ss_sold_date_sk = d_date_sk\n" +
                        "       and d_year between 1999 and 2001\n" +
                        "   union all\n" +
                        "     select cs_quantity quantity, cs_list_price list_price\n" +
                        "     from catalog_sales, date_dim\n" +
                        "     where cs_sold_date_sk = d_date_sk\n" +
                        "       and d_year between 1999 and 1999 + 2\n" +
                        "   union all\n" +
                        "     select ws_quantity quantity, ws_list_price list_price\n" +
                        "     from web_sales, date_dim\n" +
                        "     where ws_sold_date_sk = d_date_sk\n" +
                        "       and d_year between 1999 and 1999 + 2) x)\n" +
                        " select channel, i_brand_id,i_class_id,i_category_id,sum(sales) sum_sale, sum(number_sales) sum_num\n" +
                        " from(\n" +
                        "     select 'store' channel, i_brand_id,i_class_id\n" +
                        "             ,i_category_id,sum(ss_quantity*ss_list_price) sales\n" +
                        "             , count(*) number_sales\n" +
                        "     from store_sales, item, date_dim\n" +
                        "     where ss_item_sk in (select ss_item_sk from cross_items)\n" +
                        "       and ss_item_sk = i_item_sk\n" +
                        "       and ss_sold_date_sk = d_date_sk\n" +
                        "       and d_year = 1999+2\n" +
                        "       and d_moy = 11\n" +
                        "     group by i_brand_id,i_class_id,i_category_id\n" +
                        "     having sum(ss_quantity*ss_list_price) > (select average_sales from avg_sales)\n" +
                        "   union all\n" +
                        "     select 'catalog' channel, i_brand_id,i_class_id,i_category_id, sum(cs_quantity*cs_list_price) sales, count(*) number_sales\n" +
                        "     from catalog_sales, item, date_dim\n" +
                        "     where cs_item_sk in (select ss_item_sk from cross_items)\n" +
                        "       and cs_item_sk = i_item_sk\n" +
                        "       and cs_sold_date_sk = d_date_sk\n" +
                        "       and d_year = 1999+2\n" +
                        "       and d_moy = 11\n" +
                        "     group by i_brand_id,i_class_id,i_category_id\n" +
                        "     having sum(cs_quantity*cs_list_price) > (select average_sales from avg_sales)\n" +
                        "   union all\n" +
                        "     select 'web' channel, i_brand_id,i_class_id,i_category_id, sum(ws_quantity*ws_list_price) sales , count(*) number_sales\n" +
                        "     from web_sales, item, date_dim\n" +
                        "     where ws_item_sk in (select ss_item_sk from cross_items)\n" +
                        "       and ws_item_sk = i_item_sk\n" +
                        "       and ws_sold_date_sk = d_date_sk\n" +
                        "       and d_year = 1999+2\n" +
                        "       and d_moy = 11\n" +
                        "     group by i_brand_id,i_class_id,i_category_id\n" +
                        "     having sum(ws_quantity*ws_list_price) > (select average_sales from avg_sales)\n" +
                        " ) y\n" +
                        " group by rollup (channel, i_brand_id,i_class_id,i_category_id)\n" +
                        " order by channel,i_brand_id,i_class_id,i_category_id\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS14B",
                " with  cross_items as\n" +
                        " (select i_item_sk ss_item_sk\n" +
                        "  from item,\n" +
                        "     (select iss.i_brand_id brand_id, iss.i_class_id class_id, iss.i_category_id category_id\n" +
                        "      from store_sales, item iss, date_dim d1\n" +
                        "      where ss_item_sk = iss.i_item_sk\n" +
                        "         and ss_sold_date_sk = d1.d_date_sk\n" +
                        "         and d1.d_year between 1999 AND 1999 + 2\n" +
                        "     intersect\n" +
                        "       select ics.i_brand_id, ics.i_class_id, ics.i_category_id\n" +
                        "       from catalog_sales, item ics, date_dim d2\n" +
                        "       where cs_item_sk = ics.i_item_sk\n" +
                        "         and cs_sold_date_sk = d2.d_date_sk\n" +
                        "         and d2.d_year between 1999 AND 1999 + 2\n" +
                        "     intersect\n" +
                        "       select iws.i_brand_id, iws.i_class_id, iws.i_category_id\n" +
                        "       from web_sales, item iws, date_dim d3\n" +
                        "       where ws_item_sk = iws.i_item_sk\n" +
                        "         and ws_sold_date_sk = d3.d_date_sk\n" +
                        "         and d3.d_year between 1999 AND 1999 + 2) x\n" +
                        "  where i_brand_id = brand_id\n" +
                        "    and i_class_id = class_id\n" +
                        "    and i_category_id = category_id\n" +
                        " ),\n" +
                        " avg_sales as\n" +
                        " (select avg(quantity*list_price) average_sales\n" +
                        "  from (select ss_quantity quantity, ss_list_price list_price\n" +
                        "         from store_sales, date_dim\n" +
                        "         where ss_sold_date_sk = d_date_sk and d_year between 1999 and 1999 + 2\n" +
                        "       union all\n" +
                        "         select cs_quantity quantity, cs_list_price list_price\n" +
                        "         from catalog_sales, date_dim\n" +
                        "         where cs_sold_date_sk = d_date_sk and d_year between 1999 and 1999 + 2\n" +
                        "       union all\n" +
                        "         select ws_quantity quantity, ws_list_price list_price\n" +
                        "         from web_sales, date_dim\n" +
                        "         where ws_sold_date_sk = d_date_sk and d_year between 1999 and 1999 + 2) x)\n" +
                        " select * from\n" +
                        " (select 'store' channel, i_brand_id,i_class_id,i_category_id\n" +
                        "        ,sum(ss_quantity*ss_list_price) sales, count(*) number_sales\n" +
                        "  from store_sales, item, date_dim\n" +
                        "  where ss_item_sk in (select ss_item_sk from cross_items)\n" +
                        "    and ss_item_sk = i_item_sk\n" +
                        "    and ss_sold_date_sk = d_date_sk\n" +
                        "    and d_week_seq = (select d_week_seq from date_dim\n" +
                        "                     where d_year = 1999 + 1 and d_moy = 12 and d_dom = 11)\n" +
                        "  group by i_brand_id,i_class_id,i_category_id\n" +
                        "  having sum(ss_quantity*ss_list_price) > (select average_sales from avg_sales)) this_year,\n" +
                        " (select 'store' channel, i_brand_id,i_class_id\n" +
                        "        ,i_category_id, sum(ss_quantity*ss_list_price) sales, count(*) number_sales\n" +
                        " from store_sales, item, date_dim\n" +
                        " where ss_item_sk in (select ss_item_sk from cross_items)\n" +
                        "   and ss_item_sk = i_item_sk\n" +
                        "   and ss_sold_date_sk = d_date_sk\n" +
                        "   and d_week_seq = (select d_week_seq from date_dim\n" +
                        "                     where d_year = 1999 and d_moy = 12 and d_dom = 11)\n" +
                        " group by i_brand_id,i_class_id,i_category_id\n" +
                        " having sum(ss_quantity*ss_list_price) > (select average_sales from avg_sales)) last_year\n" +
                        " where this_year.i_brand_id= last_year.i_brand_id\n" +
                        "   and this_year.i_class_id = last_year.i_class_id\n" +
                        "   and this_year.i_category_id = last_year.i_category_id\n" +
                        " order by this_year.channel, this_year.i_brand_id, this_year.i_class_id, this_year.i_category_id\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS15",
                " select ca_zip, sum(cs_sales_price) sum_price\n" +
                        " from catalog_sales, customer, customer_address, date_dim\n" +
                        " where cs_bill_customer_sk = c_customer_sk\n" +
                        " \tand c_current_addr_sk = ca_address_sk\n" +
                        " \tand ( substr(ca_zip,1,5) in ('85669', '86197','88274','83405','86475',\n" +
                        "                                   '85392', '85460', '80348', '81792')\n" +
                        " \t      or ca_state in ('CA','WA','GA')\n" +
                        " \t      or cs_sales_price > 500)\n" +
                        " \tand cs_sold_date_sk = d_date_sk\n" +
                        " \tand d_qoy = 2 and d_year = 2001\n" +
                        " group by ca_zip\n" +
                        " order by ca_zip\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS16",
                " select\n" +
                        "   count(distinct cs_order_number) as order_count,\n" +
                        "   sum(cs_ext_ship_cost) as total_shipping_cost,\n" +
                        "   sum(cs_net_profit) as total_net_profit\n" +
                        " from\n" +
                        "   catalog_sales cs1, date_dim, customer_address, call_center\n" +
                        " where\n" +
                        "   d_date between cast ('2002-02-01' as date) and (cast('2002-02-01' as date) + interval '60' day)\n" +
                        " and cs1.cs_ship_date_sk = d_date_sk\n" +
                        " and cs1.cs_ship_addr_sk = ca_address_sk\n" +
                        " and ca_state = 'GA'\n" +
                        " and cs1.cs_call_center_sk = cc_call_center_sk\n" +
                        " and cc_county in ('Williamson County','Williamson County','Williamson County','Williamson County', 'Williamson County') \n" +
                        " and exists (select *\n" +
                        "            from catalog_sales cs2\n" +
                        "            where cs1.cs_order_number = cs2.cs_order_number\n" +
                        "              and cs1.cs_warehouse_sk <> cs2.cs_warehouse_sk)\n" +
                        " and not exists(select *\n" +
                        "               from catalog_returns cr1\n" +
                        "               where cs1.cs_order_number = cr1.cr_order_number)\n" +
                        " order by count(distinct cs_order_number)\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS17",
                "select i_item_id\n" +
                        "       ,i_item_desc\n" +
                        "       ,s_state\n" +
                        "       ,count(ss_quantity) as store_sales_quantitycount\n" +
                        "       ,avg(ss_quantity) as store_sales_quantityave\n" +
                        "       ,stddev_samp(ss_quantity) as store_sales_quantitystdev\n" +
                        "       ,stddev_samp(ss_quantity)/avg(ss_quantity) as store_sales_quantitycov\n" +
                        "       ,count(sr_return_quantity) as_store_returns_quantitycount\n" +
                        "       ,avg(sr_return_quantity) as_store_returns_quantityave\n" +
                        "       ,stddev_samp(sr_return_quantity) as_store_returns_quantitystdev\n" +
                        "       ,stddev_samp(sr_return_quantity)/avg(sr_return_quantity) as store_returns_quantitycov\n" +
                        "       ,count(cs_quantity) as catalog_sales_quantitycount ,avg(cs_quantity) as catalog_sales_quantityave\n" +
                        "       ,stddev_samp(cs_quantity)/avg(cs_quantity) as catalog_sales_quantitystdev\n" +
                        "       ,stddev_samp(cs_quantity)/avg(cs_quantity) as catalog_sales_quantitycov\n" +
                        " from store_sales, store_returns, catalog_sales, date_dim d1, date_dim d2, date_dim d3, store, item\n" +
                        " where d1.d_quarter_name = '2001Q1'\n" +
                        "   and d1.d_date_sk = ss_sold_date_sk\n" +
                        "   and i_item_sk = ss_item_sk\n" +
                        "   and s_store_sk = ss_store_sk\n" +
                        "   and ss_customer_sk = sr_customer_sk\n" +
                        "   and ss_item_sk = sr_item_sk\n" +
                        "   and ss_ticket_number = sr_ticket_number\n" +
                        "   and sr_returned_date_sk = d2.d_date_sk\n" +
                        "   and d2.d_quarter_name in ('2001Q1','2001Q2','2001Q3')\n" +
                        "   and sr_customer_sk = cs_bill_customer_sk\n" +
                        "   and sr_item_sk = cs_item_sk\n" +
                        "   and cs_sold_date_sk = d3.d_date_sk\n" +
                        "   and d3.d_quarter_name in ('2001Q1','2001Q2','2001Q3')\n" +
                        " group by i_item_id, i_item_desc, s_state\n" +
                        " order by i_item_id, i_item_desc, s_state\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS18",
                "select i_item_id,\n" +
                        "        ca_country,\n" +
                        "        ca_state,\n" +
                        "        ca_county,\n" +
                        "        avg( cast(cs_quantity as decimal(12,2))) agg1,\n" +
                        "        avg( cast(cs_list_price as decimal(12,2))) agg2,\n" +
                        "        avg( cast(cs_coupon_amt as decimal(12,2))) agg3,\n" +
                        "        avg( cast(cs_sales_price as decimal(12,2))) agg4,\n" +
                        "        avg( cast(cs_net_profit as decimal(12,2))) agg5,\n" +
                        "        avg( cast(c_birth_year as decimal(12,2))) agg6,\n" +
                        "        avg( cast(cd1.cd_dep_count as decimal(12,2))) agg7\n" +
                        " from catalog_sales, customer_demographics cd1,\n" +
                        "      customer_demographics cd2, customer, customer_address, date_dim, item\n" +
                        " where cs_sold_date_sk = d_date_sk and\n" +
                        "       cs_item_sk = i_item_sk and\n" +
                        "       cs_bill_cdemo_sk = cd1.cd_demo_sk and\n" +
                        "       cs_bill_customer_sk = c_customer_sk and\n" +
                        "       cd1.cd_gender = 'F' and\n" +
                        "       cd1.cd_education_status = 'Unknown' and\n" +
                        "       c_current_cdemo_sk = cd2.cd_demo_sk and\n" +
                        "       c_current_addr_sk = ca_address_sk and\n" +
                        "       c_birth_month in (1,6,8,9,12,2) and\n" +
                        "       d_year = 1998 and\n" +
                        "       ca_state  in ('MS','IN','ND','OK','NM','VA','MS')\n" +
                        " group by rollup (i_item_id, ca_country, ca_state, ca_county)\n" +
                        " order by ca_country, ca_state, ca_county, i_item_id\n" +
                        " LIMIT 100"
        );
        tpcdsSQLMap.put(
                "TPCDS19",
                " select i_brand_id brand_id, i_brand brand, i_manufact_id, i_manufact,\n" +
                        " \tsum(ss_ext_sales_price) ext_price\n" +
                        " from date_dim, store_sales, item,customer,customer_address,store\n" +
                        " where d_date_sk = ss_sold_date_sk\n" +
                        "   and ss_item_sk = i_item_sk\n" +
                        "   and i_manager_id = 8\n" +
                        "   and d_moy = 11\n" +
                        "   and d_year = 1998\n" +
                        "   and ss_customer_sk = c_customer_sk\n" +
                        "   and c_current_addr_sk = ca_address_sk\n" +
                        "   and substr(ca_zip,1,5) <> substr(s_zip,1,5)\n" +
                        "   and ss_store_sk = s_store_sk\n" +
                        " group by i_brand, i_brand_id, i_manufact_id, i_manufact\n" +
                        " order by ext_price desc, brand, brand_id, i_manufact_id, i_manufact\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS20",
                "select i_item_id, i_item_desc\n" +
                        "       ,i_category\n" +
                        "       ,i_class\n" +
                        "       ,i_current_price\n" +
                        "       ,sum(cs_ext_sales_price) as itemrevenue\n" +
                        "       ,sum(cs_ext_sales_price)*100/sum(sum(cs_ext_sales_price)) over\n" +
                        "           (partition by i_class) as revenueratio\n" +
                        " from catalog_sales, item, date_dim\n" +
                        " where cs_item_sk = i_item_sk\n" +
                        "   and i_category in ('Sports', 'Books', 'Home')\n" +
                        "   and cs_sold_date_sk = d_date_sk\n" +
                        " and d_date between cast('1999-02-22' as date)\n" +
                        " \t\t\t\tand (cast('1999-02-22' as date) + interval '30' day)\n" +
                        " group by i_item_id, i_item_desc, i_category, i_class, i_current_price\n" +
                        " order by i_category, i_class, i_item_id, i_item_desc, revenueratio\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS21",
                " select * from(\n" +
                        "   select w_warehouse_name, i_item_id,\n" +
                        "          sum(case when (cast(d_date as date) < cast ('2000-03-11' as date))\n" +
                        "\t                  then inv_quantity_on_hand\n" +
                        "                   else 0 end) as inv_before,\n" +
                        "          sum(case when (cast(d_date as date) >= cast ('2000-03-11' as date))\n" +
                        "                   then inv_quantity_on_hand\n" +
                        "                   else 0 end) as inv_after\n" +
                        "   from inventory, warehouse, item, date_dim\n" +
                        "   where i_current_price between 0.99 and 1.49\n" +
                        "     and i_item_sk          = inv_item_sk\n" +
                        "     and inv_warehouse_sk   = w_warehouse_sk\n" +
                        "     and inv_date_sk        = d_date_sk\n" +
                        "     and d_date between (cast('2000-03-11' as date) - interval '30' day)\n" +
                        "                    and (cast('2000-03-11' as date) + interval '30' day)\n" +
                        "   group by w_warehouse_name, i_item_id) x\n" +
                        " where (case when inv_before > 0\n" +
                        "             then inv_after / inv_before\n" +
                        "             else null\n" +
                        "             end) between 2.0/3.0 and 3.0/2.0\n" +
                        " order by w_warehouse_name, i_item_id\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS22",
                " select i_product_name, i_brand, i_class, i_category, avg(inv_quantity_on_hand) qoh\n" +
                        "       from inventory, date_dim, item, warehouse\n" +
                        "       where inv_date_sk=d_date_sk\n" +
                        "              and inv_item_sk=i_item_sk\n" +
                        "              and inv_warehouse_sk = w_warehouse_sk\n" +
                        "              and d_month_seq between 1200 and 1200 + 11\n" +
                        "       group by rollup(i_product_name, i_brand, i_class, i_category)\n" +
                        " order by qoh, i_product_name, i_brand, i_class, i_category\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS23A",
                "with frequent_ss_items as\n" +
                        " (select substr(i_item_desc,1,30) itemdesc,i_item_sk item_sk,d_date solddate,count(*) cnt\n" +
                        "  from store_sales, date_dim, item\n" +
                        "  where ss_sold_date_sk = d_date_sk\n" +
                        "    and ss_item_sk = i_item_sk\n" +
                        "    and d_year in (2000, 2000+1, 2000+2,2000+3)\n" +
                        "  group by substr(i_item_desc,1,30),i_item_sk,d_date\n" +
                        "  having count(*) >4),\n" +
                        " max_store_sales as\n" +
                        " (select max(csales) tpcds_cmax\n" +
                        "  from (select c_customer_sk,sum(ss_quantity*ss_sales_price) csales\n" +
                        "        from store_sales, customer, date_dim\n" +
                        "        where ss_customer_sk = c_customer_sk\n" +
                        "         and ss_sold_date_sk = d_date_sk\n" +
                        "         and d_year in (2000, 2000+1, 2000+2,2000+3)\n" +
                        "        group by c_customer_sk)),\n" +
                        " best_ss_customer as\n" +
                        " (select c_customer_sk,sum(ss_quantity*ss_sales_price) ssales\n" +
                        "  from store_sales, customer\n" +
                        "  where ss_customer_sk = c_customer_sk\n" +
                        "  group by c_customer_sk\n" +
                        "  having sum(ss_quantity*ss_sales_price) > (95/100.0) *\n" +
                        "    (select * from max_store_sales))\n" +
                        " select sum(sales) sum_sales\n" +
                        " from (select cs_quantity*cs_list_price sales\n" +
                        "       from catalog_sales, date_dim\n" +
                        "       where d_year = 2000\n" +
                        "         and d_moy = 2\n" +
                        "         and cs_sold_date_sk = d_date_sk\n" +
                        "         and cs_item_sk in (select item_sk from frequent_ss_items)\n" +
                        "         and cs_bill_customer_sk in (select c_customer_sk from best_ss_customer)\n" +
                        "      union all\n" +
                        "      (select ws_quantity*ws_list_price sales\n" +
                        "       from web_sales, date_dim\n" +
                        "       where d_year = 2000\n" +
                        "         and d_moy = 2\n" +
                        "         and ws_sold_date_sk = d_date_sk\n" +
                        "         and ws_item_sk in (select item_sk from frequent_ss_items)\n" +
                        "         and ws_bill_customer_sk in (select c_customer_sk from best_ss_customer)))\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS23B",
                " with frequent_ss_items as\n" +
                        " (select substr(i_item_desc,1,30) itemdesc,i_item_sk item_sk,d_date solddate,count(*) cnt\n" +
                        "  from store_sales, date_dim, item\n" +
                        "  where ss_sold_date_sk = d_date_sk\n" +
                        "    and ss_item_sk = i_item_sk\n" +
                        "    and d_year in (2000, 2000+1, 2000+2,2000+3)\n" +
                        "  group by substr(i_item_desc,1,30),i_item_sk,d_date\n" +
                        "  having count(*) > 4),\n" +
                        " max_store_sales as\n" +
                        " (select max(csales) tpcds_cmax\n" +
                        "  from (select c_customer_sk,sum(ss_quantity*ss_sales_price) csales\n" +
                        "        from store_sales, customer, date_dim\n" +
                        "        where ss_customer_sk = c_customer_sk\n" +
                        "         and ss_sold_date_sk = d_date_sk\n" +
                        "         and d_year in (2000, 2000+1, 2000+2,2000+3)\n" +
                        "        group by c_customer_sk) x),\n" +
                        " best_ss_customer as\n" +
                        " (select c_customer_sk,sum(ss_quantity*ss_sales_price) ssales\n" +
                        "  from store_sales\n" +
                        "      ,customer\n" +
                        "  where ss_customer_sk = c_customer_sk\n" +
                        "  group by c_customer_sk\n" +
                        "  having sum(ss_quantity*ss_sales_price) > (95/100.0) *\n" +
                        "    (select * from max_store_sales))\n" +
                        " select c_last_name,c_first_name,sales\n" +
                        " from ((select c_last_name,c_first_name,sum(cs_quantity*cs_list_price) sales\n" +
                        "        from catalog_sales, customer, date_dim\n" +
                        "        where d_year = 2000\n" +
                        "         and d_moy = 2\n" +
                        "         and cs_sold_date_sk = d_date_sk\n" +
                        "         and cs_item_sk in (select item_sk from frequent_ss_items)\n" +
                        "         and cs_bill_customer_sk in (select c_customer_sk from best_ss_customer)\n" +
                        "         and cs_bill_customer_sk = c_customer_sk\n" +
                        "       group by c_last_name,c_first_name)\n" +
                        "      union all\n" +
                        "      (select c_last_name,c_first_name,sum(ws_quantity*ws_list_price) sales\n" +
                        "       from web_sales, customer, date_dim\n" +
                        "       where d_year = 2000\n" +
                        "         and d_moy = 2\n" +
                        "         and ws_sold_date_sk = d_date_sk\n" +
                        "         and ws_item_sk in (select item_sk from frequent_ss_items)\n" +
                        "         and ws_bill_customer_sk in (select c_customer_sk from best_ss_customer)\n" +
                        "         and ws_bill_customer_sk = c_customer_sk\n" +
                        "       group by c_last_name,c_first_name)) y\n" +
                        "     order by c_last_name,c_first_name,sales\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS24A",
                " with ssales as\n" +
                        " (select c_last_name, c_first_name, s_store_name, ca_state, s_state, i_color,\n" +
                        "        i_current_price, i_manager_id, i_units, i_size, sum(ss_net_paid) netpaid\n" +
                        " from store_sales, store_returns, store, item, customer, customer_address\n" +
                        " where ss_ticket_number = sr_ticket_number\n" +
                        "   and ss_item_sk = sr_item_sk\n" +
                        "   and ss_customer_sk = c_customer_sk\n" +
                        "   and ss_item_sk = i_item_sk\n" +
                        "   and ss_store_sk = s_store_sk\n" +
                        "   and c_birth_country = upper(ca_country)\n" +
                        "   and s_zip = ca_zip\n" +
                        " and s_market_id = 8\n" +
                        " group by c_last_name, c_first_name, s_store_name, ca_state, s_state, i_color,\n" +
                        "          i_current_price, i_manager_id, i_units, i_size)\n" +
                        " select c_last_name, c_first_name, s_store_name, sum(netpaid) paid\n" +
                        " from ssales\n" +
                        " where i_color = 'pale'\n" +
                        " group by c_last_name, c_first_name, s_store_name\n" +
                        " having sum(netpaid) > (select 0.05*avg(netpaid) from ssales)"
        );
        tpcdsSQLMap.put(
                "TPCDS24B",
                " with ssales as\n" +
                        " (select c_last_name, c_first_name, s_store_name, ca_state, s_state, i_color,\n" +
                        "         i_current_price, i_manager_id, i_units, i_size, sum(ss_net_paid) netpaid\n" +
                        " from store_sales, store_returns, store, item, customer, customer_address\n" +
                        " where ss_ticket_number = sr_ticket_number\n" +
                        "   and ss_item_sk = sr_item_sk\n" +
                        "   and ss_customer_sk = c_customer_sk\n" +
                        "   and ss_item_sk = i_item_sk\n" +
                        "   and ss_store_sk = s_store_sk\n" +
                        "   and c_birth_country = upper(ca_country)\n" +
                        "   and s_zip = ca_zip\n" +
                        "   and s_market_id = 8\n" +
                        " group by c_last_name, c_first_name, s_store_name, ca_state, s_state,\n" +
                        "          i_color, i_current_price, i_manager_id, i_units, i_size)\n" +
                        " select c_last_name, c_first_name, s_store_name, sum(netpaid) paid\n" +
                        " from ssales\n" +
                        " where i_color = 'chiffon'\n" +
                        " group by c_last_name, c_first_name, s_store_name\n" +
                        " having sum(netpaid) > (select 0.05*avg(netpaid) from ssales)"
        );
        tpcdsSQLMap.put(
                "TPCDS25",
                " select i_item_id, i_item_desc, s_store_id, s_store_name,\n" +
                        "    sum(ss_net_profit) as store_sales_profit,\n" +
                        "    sum(sr_net_loss) as store_returns_loss,\n" +
                        "    sum(cs_net_profit) as catalog_sales_profit\n" +
                        " from\n" +
                        "    store_sales, store_returns, catalog_sales, date_dim d1, date_dim d2, date_dim d3,\n" +
                        "    store, item\n" +
                        " where\n" +
                        "    d1.d_moy = 4\n" +
                        "    and d1.d_year = 2001\n" +
                        "    and d1.d_date_sk = ss_sold_date_sk\n" +
                        "    and i_item_sk = ss_item_sk\n" +
                        "    and s_store_sk = ss_store_sk\n" +
                        "    and ss_customer_sk = sr_customer_sk\n" +
                        "    and ss_item_sk = sr_item_sk\n" +
                        "    and ss_ticket_number = sr_ticket_number\n" +
                        "    and sr_returned_date_sk = d2.d_date_sk\n" +
                        "    and d2.d_moy between 4 and 10\n" +
                        "    and d2.d_year = 2001\n" +
                        "    and sr_customer_sk = cs_bill_customer_sk\n" +
                        "    and sr_item_sk = cs_item_sk\n" +
                        "    and cs_sold_date_sk = d3.d_date_sk\n" +
                        "    and d3.d_moy between 4 and 10\n" +
                        "    and d3.d_year = 2001\n" +
                        " group by\n" +
                        "    i_item_id, i_item_desc, s_store_id, s_store_name\n" +
                        " order by\n" +
                        "    i_item_id, i_item_desc, s_store_id, s_store_name\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS26",
                " select i_item_id,\n" +
                        "        avg(cs_quantity) agg1,\n" +
                        "        avg(cs_list_price) agg2,\n" +
                        "        avg(cs_coupon_amt) agg3,\n" +
                        "        avg(cs_sales_price) agg4\n" +
                        " from catalog_sales, customer_demographics, date_dim, item, promotion\n" +
                        " where cs_sold_date_sk = d_date_sk and\n" +
                        "       cs_item_sk = i_item_sk and\n" +
                        "       cs_bill_cdemo_sk = cd_demo_sk and\n" +
                        "       cs_promo_sk = p_promo_sk and\n" +
                        "       cd_gender = 'M' and\n" +
                        "       cd_marital_status = 'S' and\n" +
                        "       cd_education_status = 'College' and\n" +
                        "       (p_channel_email = 'N' or p_channel_event = 'N') and\n" +
                        "       d_year = 2000\n" +
                        " group by i_item_id\n" +
                        " order by i_item_id\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS27",
                " select i_item_id,\n" +
                        "        s_state, grouping(s_state) g_state,\n" +
                        "        avg(ss_quantity) agg1,\n" +
                        "        avg(ss_list_price) agg2,\n" +
                        "        avg(ss_coupon_amt) agg3,\n" +
                        "        avg(ss_sales_price) agg4\n" +
                        " from store_sales, customer_demographics, date_dim, store, item\n" +
                        " where ss_sold_date_sk = d_date_sk and\n" +
                        "       ss_item_sk = i_item_sk and\n" +
                        "       ss_store_sk = s_store_sk and\n" +
                        "       ss_cdemo_sk = cd_demo_sk and\n" +
                        "       cd_gender = 'M' and\n" +
                        "       cd_marital_status = 'S' and\n" +
                        "       cd_education_status = 'College' and\n" +
                        "       d_year = 2002 and\n" +
                        "       s_state in ('TN','TN', 'TN', 'TN', 'TN', 'TN')\n" +
                        " group by rollup (i_item_id, s_state)\n" +
                        " order by i_item_id, s_state\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS28",
                "select *\n" +
                        " from (select avg(ss_list_price) B1_LP\n" +
                        "            ,count(ss_list_price) B1_CNT\n" +
                        "            ,count(distinct ss_list_price) B1_CNTD\n" +
                        "      from store_sales\n" +
                        "      where ss_quantity between 0 and 5\n" +
                        "        and (ss_list_price between 8 and 8+10 \n" +
                        "             or ss_coupon_amt between 459 and 459+1000\n" +
                        "             or ss_wholesale_cost between 57 and 57+20)) B1 cross join\n" +
                        "     (select avg(ss_list_price) B2_LP\n" +
                        "            ,count(ss_list_price) B2_CNT\n" +
                        "            ,count(distinct ss_list_price) B2_CNTD\n" +
                        "      from store_sales\n" +
                        "      where ss_quantity between 6 and 10\n" +
                        "        and (ss_list_price between 90 and 90+10\n" +
                        "             or ss_coupon_amt between 2323 and 2323+1000\n" +
                        "             or ss_wholesale_cost between 31 and 31+20)) B2 cross join\n" +
                        "     (select avg(ss_list_price) B3_LP\n" +
                        "            ,count(ss_list_price) B3_CNT\n" +
                        "            ,count(distinct ss_list_price) B3_CNTD\n" +
                        "      from store_sales\n" +
                        "      where ss_quantity between 11 and 15\n" +
                        "        and (ss_list_price between 142 and 142+10\n" +
                        "             or ss_coupon_amt between 12214 and 12214+1000\n" +
                        "             or ss_wholesale_cost between 79 and 79+20)) B3 cross join\n" +
                        "     (select avg(ss_list_price) B4_LP\n" +
                        "            ,count(ss_list_price) B4_CNT\n" +
                        "            ,count(distinct ss_list_price) B4_CNTD\n" +
                        "      from store_sales\n" +
                        "      where ss_quantity between 16 and 20\n" +
                        "        and (ss_list_price between 135 and 135+10\n" +
                        "             or ss_coupon_amt between 6071 and 6071+1000\n" +
                        "             or ss_wholesale_cost between 38 and 38+20)) B4 cross join\n" +
                        "     (select avg(ss_list_price) B5_LP\n" +
                        "            ,count(ss_list_price) B5_CNT\n" +
                        "            ,count(distinct ss_list_price) B5_CNTD\n" +
                        "      from store_sales\n" +
                        "      where ss_quantity between 21 and 25\n" +
                        "        and (ss_list_price between 122 and 122+10\n" +
                        "             or ss_coupon_amt between 836 and 836+1000\n" +
                        "             or ss_wholesale_cost between 17 and 17+20)) B5 cross join\n" +
                        "     (select avg(ss_list_price) B6_LP\n" +
                        "            ,count(ss_list_price) B6_CNT\n" +
                        "            ,count(distinct ss_list_price) B6_CNTD\n" +
                        "      from store_sales\n" +
                        "      where ss_quantity between 26 and 30\n" +
                        "        and (ss_list_price between 154 and 154+10\n" +
                        "             or ss_coupon_amt between 7326 and 7326+1000\n" +
                        "             or ss_wholesale_cost between 7 and 7+20)) B6\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS29",
                "select\n" +
                        "     i_item_id\n" +
                        "    ,i_item_desc\n" +
                        "    ,s_store_id\n" +
                        "    ,s_store_name\n" +
                        "    ,sum(ss_quantity)        as store_sales_quantity\n" +
                        "    ,sum(sr_return_quantity) as store_returns_quantity\n" +
                        "    ,sum(cs_quantity)        as catalog_sales_quantity\n" +
                        " from\n" +
                        "    store_sales, store_returns, catalog_sales, date_dim d1, date_dim d2,\n" +
                        "    date_dim d3, store, item\n" +
                        " where\n" +
                        "     d1.d_moy               = 9\n" +
                        " and d1.d_year              = 1999\n" +
                        " and d1.d_date_sk           = ss_sold_date_sk\n" +
                        " and i_item_sk              = ss_item_sk\n" +
                        " and s_store_sk             = ss_store_sk\n" +
                        " and ss_customer_sk         = sr_customer_sk\n" +
                        " and ss_item_sk             = sr_item_sk\n" +
                        " and ss_ticket_number       = sr_ticket_number\n" +
                        " and sr_returned_date_sk    = d2.d_date_sk\n" +
                        " and d2.d_moy               between 9 and  9 + 3\n" +
                        " and d2.d_year              = 1999\n" +
                        " and sr_customer_sk         = cs_bill_customer_sk\n" +
                        " and sr_item_sk             = cs_item_sk\n" +
                        " and cs_sold_date_sk        = d3.d_date_sk\n" +
                        " and d3.d_year              in (1999,1999+1,1999+2)\n" +
                        " group by\n" +
                        "    i_item_id, i_item_desc, s_store_id, s_store_name\n" +
                        " order by\n" +
                        "    i_item_id, i_item_desc, s_store_id, s_store_name\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS30",
                "with customer_total_return as\n" +
                        " (select wr_returning_customer_sk as ctr_customer_sk\n" +
                        "        ,ca_state as ctr_state,\n" +
                        " \tsum(wr_return_amt) as ctr_total_return\n" +
                        " from web_returns, date_dim, customer_address\n" +
                        " where wr_returned_date_sk = d_date_sk\n" +
                        "   and d_year = 2002\n" +
                        "   and wr_returning_addr_sk = ca_address_sk\n" +
                        " group by wr_returning_customer_sk,ca_state)\n" +
                        " select c_customer_id,c_salutation,c_first_name,c_last_name,c_preferred_cust_flag\n" +
                        "       ,c_birth_day,c_birth_month,c_birth_year,c_birth_country,c_login,c_email_address\n" +
                        "       ,c_last_review_date_sk,ctr_total_return\n" +
                        " from customer_total_return ctr1, customer_address, customer\n" +
                        " where ctr1.ctr_total_return > (select avg(ctr_total_return)*1.2\n" +
                        " \t\t\t  from customer_total_return ctr2\n" +
                        "                  \t  where ctr1.ctr_state = ctr2.ctr_state)\n" +
                        "       and ca_address_sk = c_current_addr_sk\n" +
                        "       and ca_state = 'GA'\n" +
                        "       and ctr1.ctr_customer_sk = c_customer_sk\n" +
                        " order by c_customer_id,c_salutation,c_first_name,c_last_name,c_preferred_cust_flag\n" +
                        "                  ,c_birth_day,c_birth_month,c_birth_year,c_birth_country,c_login,c_email_address\n" +
                        "                  ,c_last_review_date_sk,ctr_total_return\n" +
                        " limit 100\n" +
                        " "
        );
        tpcdsSQLMap.put(
                "TPCDS31",
                " with ss as\n" +
                        " (select ca_county,d_qoy, d_year,sum(ss_ext_sales_price) as store_sales\n" +
                        " from store_sales,date_dim,customer_address\n" +
                        " where ss_sold_date_sk = d_date_sk\n" +
                        "  and ss_addr_sk=ca_address_sk\n" +
                        " group by ca_county,d_qoy, d_year),\n" +
                        " ws as\n" +
                        " (select ca_county,d_qoy, d_year,sum(ws_ext_sales_price) as web_sales\n" +
                        " from web_sales,date_dim,customer_address\n" +
                        " where ws_sold_date_sk = d_date_sk\n" +
                        "  and ws_bill_addr_sk=ca_address_sk\n" +
                        " group by ca_county,d_qoy, d_year)\n" +
                        " select\n" +
                        "        ss1.ca_county\n" +
                        "       ,ss1.d_year\n" +
                        "       ,ws2.web_sales/ws1.web_sales web_q1_q2_increase\n" +
                        "       ,ss2.store_sales/ss1.store_sales store_q1_q2_increase\n" +
                        "       ,ws3.web_sales/ws2.web_sales web_q2_q3_increase\n" +
                        "       ,ss3.store_sales/ss2.store_sales store_q2_q3_increase\n" +
                        " from\n" +
                        "        ss ss1, ss ss2, ss ss3, ws ws1, ws ws2, ws ws3\n" +
                        " where\n" +
                        "    ss1.d_qoy = 1\n" +
                        "    and ss1.d_year = 2000\n" +
                        "    and ss1.ca_county = ss2.ca_county\n" +
                        "    and ss2.d_qoy = 2\n" +
                        "    and ss2.d_year = 2000\n" +
                        " and ss2.ca_county = ss3.ca_county\n" +
                        "    and ss3.d_qoy = 3\n" +
                        "    and ss3.d_year = 2000\n" +
                        "    and ss1.ca_county = ws1.ca_county\n" +
                        "    and ws1.d_qoy = 1\n" +
                        "    and ws1.d_year = 2000\n" +
                        "    and ws1.ca_county = ws2.ca_county\n" +
                        "    and ws2.d_qoy = 2\n" +
                        "    and ws2.d_year = 2000\n" +
                        "    and ws1.ca_county = ws3.ca_county\n" +
                        "    and ws3.d_qoy = 3\n" +
                        "    and ws3.d_year = 2000\n" +
                        "    and case when ws1.web_sales > 0 then ws2.web_sales/ws1.web_sales else null end\n" +
                        "       > case when ss1.store_sales > 0 then ss2.store_sales/ss1.store_sales else null end\n" +
                        "    and case when ws2.web_sales > 0 then ws3.web_sales/ws2.web_sales else null end\n" +
                        "       > case when ss2.store_sales > 0 then ss3.store_sales/ss2.store_sales else null end\n" +
                        " order by ss1.ca_county"
        );
        tpcdsSQLMap.put(
                "TPCDS32",
                " select sum(cs_ext_discount_amt) as excess_discount_amount\n" +
                        " from\n" +
                        "    catalog_sales, item, date_dim\n" +
                        " where\n" +
                        "   i_manufact_id = 977\n" +
                        "   and i_item_sk = cs_item_sk\n" +
                        "   and d_date between cast ('2000-01-27' as date) and (cast('2000-01-27' as date) + interval '90' day)\n" +
                        "   and d_date_sk = cs_sold_date_sk\n" +
                        "   and cs_ext_discount_amt > (\n" +
                        "          select 1.3 * avg(cs_ext_discount_amt)\n" +
                        "          from catalog_sales, date_dim\n" +
                        "          where cs_item_sk = i_item_sk\n" +
                        "           and d_date between cast ('2000-01-27' as date) and (cast('2000-01-27' as date) + interval '90' day)\n" +
                        "           and d_date_sk = cs_sold_date_sk)\n" +
                        "limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS33",
                "with ss as (\n" +
                        "    select\n" +
                        "        i_manufact_id,sum(ss_ext_sales_price) total_sales\n" +
                        "    from\n" +
                        " \t      store_sales, date_dim, customer_address, item\n" +
                        "    where\n" +
                        "        i_manufact_id in (select i_manufact_id\n" +
                        "                          from item\n" +
                        "                          where i_category in ('Electronics'))\n" +
                        "                            and ss_item_sk = i_item_sk\n" +
                        "                            and ss_sold_date_sk = d_date_sk\n" +
                        "                            and d_year = 1998\n" +
                        "                            and d_moy = 5\n" +
                        "                            and ss_addr_sk = ca_address_sk\n" +
                        "                            and ca_gmt_offset = -5\n" +
                        "                          group by i_manufact_id), cs as\n" +
                        "         (select i_manufact_id, sum(cs_ext_sales_price) total_sales\n" +
                        "          from catalog_sales, date_dim, customer_address, item\n" +
                        "          where\n" +
                        "            i_manufact_id in (\n" +
                        "                select i_manufact_id from item\n" +
                        "                where\n" +
                        "                    i_category in ('Electronics'))\n" +
                        "                    and cs_item_sk = i_item_sk\n" +
                        "                    and cs_sold_date_sk = d_date_sk\n" +
                        "                    and d_year = 1998\n" +
                        "                    and d_moy = 5\n" +
                        "                    and cs_bill_addr_sk = ca_address_sk\n" +
                        "                    and ca_gmt_offset = -5\n" +
                        "                group by i_manufact_id),\n" +
                        " ws as (\n" +
                        " select i_manufact_id,sum(ws_ext_sales_price) total_sales\n" +
                        " from\n" +
                        " \t  web_sales, date_dim, customer_address, item\n" +
                        " where\n" +
                        "    i_manufact_id in (select i_manufact_id from item\n" +
                        "                      where i_category in ('Electronics'))\n" +
                        "                          and ws_item_sk = i_item_sk\n" +
                        "                          and ws_sold_date_sk = d_date_sk\n" +
                        "                          and d_year = 1998\n" +
                        "                          and d_moy = 5\n" +
                        "                          and ws_bill_addr_sk = ca_address_sk\n" +
                        "                          and ca_gmt_offset = -5\n" +
                        "                      group by i_manufact_id)\n" +
                        " select i_manufact_id ,sum(total_sales) total_sales\n" +
                        " from  (select * from ss\n" +
                        "        union all\n" +
                        "        select * from cs\n" +
                        "        union all\n" +
                        "        select * from ws) tmp1\n" +
                        " group by i_manufact_id\n" +
                        " order by total_sales\n" +
                        "limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS34",
                " select c_last_name, c_first_name, c_salutation, c_preferred_cust_flag, ss_ticket_number,\n" +
                        "        cnt\n" +
                        " FROM\n" +
                        "   (select ss_ticket_number, ss_customer_sk, count(*) cnt\n" +
                        "    from store_sales,date_dim,store,household_demographics\n" +
                        "    where store_sales.ss_sold_date_sk = date_dim.d_date_sk\n" +
                        "    and store_sales.ss_store_sk = store.s_store_sk\n" +
                        "    and store_sales.ss_hdemo_sk = household_demographics.hd_demo_sk\n" +
                        "    and (date_dim.d_dom between 1 and 3 or date_dim.d_dom between 25 and 28)\n" +
                        "    and (household_demographics.hd_buy_potential = '>10000' or\n" +
                        "         household_demographics.hd_buy_potential = 'unknown')\n" +
                        "    and household_demographics.hd_vehicle_count > 0\n" +
                        "    and (case when household_demographics.hd_vehicle_count > 0\n" +
                        "\tthen household_demographics.hd_dep_count/ household_demographics.hd_vehicle_count\n" +
                        "\telse null\n" +
                        "\tend)  > 1.2\n" +
                        "    and date_dim.d_year in (1999, 1999+1, 1999+2)\n" +
                        "    and store.s_county in ('Williamson County','Williamson County','Williamson County','Williamson County',\n" +
                        "                           'Williamson County','Williamson County','Williamson County','Williamson County')\n" +
                        "    group by ss_ticket_number,ss_customer_sk) dn,customer\n" +
                        "    where ss_customer_sk = c_customer_sk\n" +
                        "      and cnt between 15 and 20\n" +
                        "    order by c_last_name,c_first_name,c_salutation,c_preferred_cust_flag desc, ss_ticket_number"
        );
        tpcdsSQLMap.put(
                "TPCDS35",
                " select\n" +
                        "  ca_state,\n" +
                        "  cd_gender,\n" +
                        "  cd_marital_status,\n" +
                        "  cd_dep_count,\n" +
                        "  count(*) cnt1,\n" +
                        "  min(cd_dep_count) min_dep, \n" +
                        "  max(cd_dep_count) max_dep, \n" +
                        "  avg(cd_dep_count) avg_dep, \n" +
                        "  cd_dep_employed_count,\n" +
                        "  count(*) cnt2,\n" +
                        "  min(cd_dep_employed_count) min_emp, \n" +
                        "  max(cd_dep_employed_count) max_emp,\n" +
                        "  avg(cd_dep_employed_count) avg_emp,\n" +
                        "  cd_dep_college_count,\n" +
                        "  count(*) cnt3,\n" +
                        "  min(cd_dep_college_count) min_coll,\n" +
                        "  max(cd_dep_college_count) max_coll,\n" +
                        "  avg(cd_dep_college_count) avg_coll\n" +
                        " from\n" +
                        "  customer c,customer_address ca,customer_demographics\n" +
                        " where\n" +
                        "  c.c_current_addr_sk = ca.ca_address_sk and\n" +
                        "  cd_demo_sk = c.c_current_cdemo_sk and\n" +
                        "  exists (select * from store_sales, date_dim\n" +
                        "          where c.c_customer_sk = ss_customer_sk and\n" +
                        "                ss_sold_date_sk = d_date_sk and\n" +
                        "                d_year = 2002 and\n" +
                        "                d_qoy < 4) and\n" +
                        "   (exists (select * from web_sales, date_dim\n" +
                        "            where c.c_customer_sk = ws_bill_customer_sk and\n" +
                        "                  ws_sold_date_sk = d_date_sk and\n" +
                        "                  d_year = 2002 and\n" +
                        "                  d_qoy < 4) or\n" +
                        "    exists (select * from catalog_sales, date_dim\n" +
                        "            where c.c_customer_sk = cs_ship_customer_sk and\n" +
                        "                  cs_sold_date_sk = d_date_sk and\n" +
                        "                  d_year = 2002 and\n" +
                        "                  d_qoy < 4))\n" +
                        " group by ca_state, cd_gender, cd_marital_status, cd_dep_count,\n" +
                        "          cd_dep_employed_count, cd_dep_college_count\n" +
                        " order by ca_state, cd_gender, cd_marital_status, cd_dep_count,\n" +
                        "          cd_dep_employed_count, cd_dep_college_count\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS36",
                "select\n" +
                        "    sum(ss_net_profit)/sum(ss_ext_sales_price) as gross_margin\n" +
                        "   ,i_category\n" +
                        "   ,i_class\n" +
                        "   ,grouping(i_category)+grouping(i_class) as lochierarchy\n" +
                        "   ,rank() over (\n" +
                        " \tpartition by grouping(i_category)+grouping(i_class),\n" +
                        " \tcase when grouping(i_class) = 0 then i_category end\n" +
                        " \torder by sum(ss_net_profit)/sum(ss_ext_sales_price) asc) as rank_within_parent\n" +
                        " from\n" +
                        "    store_sales, date_dim d1, item, store\n" +
                        " where\n" +
                        "    d1.d_year = 2001\n" +
                        "    and d1.d_date_sk = ss_sold_date_sk\n" +
                        "    and i_item_sk  = ss_item_sk\n" +
                        "    and s_store_sk  = ss_store_sk\n" +
                        "    and s_state in ('TN','TN','TN','TN','TN','TN','TN','TN')\n" +
                        " group by rollup(i_category,i_class)\n" +
                        " order by\n" +
                        "   lochierarchy desc\n" +
                        "  ,case when lochierarchy = 0 then i_category end\n" +
                        "  ,rank_within_parent\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS37",
                " select i_item_id, i_item_desc, i_current_price\n" +
                        " from item, inventory, date_dim, catalog_sales\n" +
                        " where i_current_price between 68 and 68 + 30\n" +
                        "   and inv_item_sk = i_item_sk\n" +
                        "   and d_date_sk=inv_date_sk\n" +
                        "   and d_date between cast('2000-02-01' as date) and (cast('2000-02-01' as date) + interval '60' day)\n" +
                        "   and i_manufact_id in (677,940,694,808)\n" +
                        "   and inv_quantity_on_hand between 100 and 500\n" +
                        "   and cs_item_sk = i_item_sk\n" +
                        " group by i_item_id,i_item_desc,i_current_price\n" +
                        " order by i_item_id\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS38",
                " select count(*) count_1 from (\n" +
                        "    select distinct c_last_name, c_first_name, d_date\n" +
                        "    from store_sales, date_dim, customer\n" +
                        "          where store_sales.ss_sold_date_sk = date_dim.d_date_sk\n" +
                        "      and store_sales.ss_customer_sk = customer.c_customer_sk\n" +
                        "      and d_month_seq between 1200 and  1200 + 11\n" +
                        "  intersect\n" +
                        "    select distinct c_last_name, c_first_name, d_date\n" +
                        "    from catalog_sales, date_dim, customer\n" +
                        "          where catalog_sales.cs_sold_date_sk = date_dim.d_date_sk\n" +
                        "      and catalog_sales.cs_bill_customer_sk = customer.c_customer_sk\n" +
                        "      and d_month_seq between  1200 and  1200 + 11\n" +
                        "  intersect\n" +
                        "    select distinct c_last_name, c_first_name, d_date\n" +
                        "    from web_sales, date_dim, customer\n" +
                        "          where web_sales.ws_sold_date_sk = date_dim.d_date_sk\n" +
                        "      and web_sales.ws_bill_customer_sk = customer.c_customer_sk\n" +
                        "      and d_month_seq between  1200 and  1200 + 11\n" +
                        " ) hot_cust\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS39A",
                " with inv as\n" +
                        " (select w_warehouse_name,w_warehouse_sk,i_item_sk,d_moy\n" +
                        "        ,stdev,mean, case mean when 0 then null else stdev/mean end cov\n" +
                        "  from(select w_warehouse_name,w_warehouse_sk,i_item_sk,d_moy\n" +
                        "             ,stddev_samp(inv_quantity_on_hand) stdev,avg(inv_quantity_on_hand) mean\n" +
                        "       from inventory, item, warehouse, date_dim\n" +
                        "       where inv_item_sk = i_item_sk\n" +
                        "         and inv_warehouse_sk = w_warehouse_sk\n" +
                        "         and inv_date_sk = d_date_sk\n" +
                        "         and d_year = 2001\n" +
                        "       group by w_warehouse_name,w_warehouse_sk,i_item_sk,d_moy) foo\n" +
                        "  where case mean when 0 then 0 else stdev/mean end > 1)\n" +
                        " select inv1.w_warehouse_sk,inv1.i_item_sk,inv1.d_moy,inv1.mean, inv1.cov\n" +
                        "         ,inv2.w_warehouse_sk,inv2.i_item_sk,inv2.d_moy,inv2.mean, inv2.cov\n" +
                        " from inv inv1,inv inv2\n" +
                        " where inv1.i_item_sk = inv2.i_item_sk\n" +
                        "   and inv1.w_warehouse_sk =  inv2.w_warehouse_sk\n" +
                        "   and inv1.d_moy=1\n" +
                        "   and inv2.d_moy=1+1\n" +
                        " order by inv1.w_warehouse_sk,inv1.i_item_sk,inv1.d_moy,inv1.mean,inv1.cov\n" +
                        "         ,inv2.d_moy,inv2.mean, inv2.cov"
        );
        tpcdsSQLMap.put(
                "TPCDS39B",
                " with inv as\n" +
                        " (select w_warehouse_name,w_warehouse_sk,i_item_sk,d_moy\n" +
                        "        ,stdev,mean, case mean when 0 then null else stdev/mean end cov\n" +
                        "  from(select w_warehouse_name,w_warehouse_sk,i_item_sk,d_moy\n" +
                        "             ,stddev_samp(inv_quantity_on_hand) stdev,avg(inv_quantity_on_hand) mean\n" +
                        "       from inventory, item, warehouse, date_dim\n" +
                        "       where inv_item_sk = i_item_sk\n" +
                        "         and inv_warehouse_sk = w_warehouse_sk\n" +
                        "         and inv_date_sk = d_date_sk\n" +
                        "         and d_year = 2001\n" +
                        "       group by w_warehouse_name,w_warehouse_sk,i_item_sk,d_moy) foo\n" +
                        "  where case mean when 0 then 0 else stdev/mean end > 1)\n" +
                        " select inv1.w_warehouse_sk,inv1.i_item_sk,inv1.d_moy,inv1.mean, inv1.cov\n" +
                        "         ,inv2.w_warehouse_sk,inv2.i_item_sk,inv2.d_moy,inv2.mean, inv2.cov\n" +
                        " from inv inv1,inv inv2\n" +
                        " where inv1.i_item_sk = inv2.i_item_sk\n" +
                        "   and inv1.w_warehouse_sk =  inv2.w_warehouse_sk\n" +
                        "   and inv1.d_moy=1\n" +
                        "   and inv2.d_moy=1+1\n" +
                        "   and inv1.cov > 1.5\n" +
                        " order by inv1.w_warehouse_sk,inv1.i_item_sk,inv1.d_moy,inv1.mean,inv1.cov\n" +
                        "         ,inv2.d_moy,inv2.mean, inv2.cov"
        );
        tpcdsSQLMap.put(
                "TPCDS40",
                " select\n" +
                        "   w_state\n" +
                        "  ,i_item_id\n" +
                        "  ,sum(case when (cast(d_date as date) < cast('2000-03-11' as date))\n" +
                        " \t\tthen cs_sales_price - coalesce(cr_refunded_cash,0) else 0 end) as sales_before\n" +
                        "  ,sum(case when (cast(d_date as date) >= cast('2000-03-11' as date))\n" +
                        " \t\tthen cs_sales_price - coalesce(cr_refunded_cash,0) else 0 end) as sales_after\n" +
                        " from\n" +
                        "   catalog_sales left outer join catalog_returns on\n" +
                        "       (cs_order_number = cr_order_number\n" +
                        "        and cs_item_sk = cr_item_sk)\n" +
                        "  ,warehouse, item, date_dim\n" +
                        " where\n" +
                        "     i_current_price between 0.99 and 1.49\n" +
                        " and i_item_sk          = cs_item_sk\n" +
                        " and cs_warehouse_sk    = w_warehouse_sk\n" +
                        " and cs_sold_date_sk    = d_date_sk\n" +
                        " and d_date between (cast('2000-03-11' as date) - interval '30' day)\n" +
                        "                and (cast('2000-03-11' as date) + interval '30' day)\n" +
                        " group by w_state,i_item_id\n" +
                        " order by w_state,i_item_id\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS41",
                "select distinct(i_product_name)\n" +
                        " from item i1\n" +
                        " where i_manufact_id between 738 and 738+40\n" +
                        "   and (select count(*) as item_cnt\n" +
                        "        from item\n" +
                        "        where (i_manufact = i1.i_manufact and\n" +
                        "        ((i_category = 'Women' and \n" +
                        "        (i_color = 'powder' or i_color = 'khaki') and\n" +
                        "        (i_units = 'Ounce' or i_units = 'Oz') and\n" +
                        "        (i_size = 'medium' or i_size = 'extra large')\n" +
                        "        ) or\n" +
                        "        (i_category = 'Women' and\n" +
                        "        (i_color = 'brown' or i_color = 'honeydew') and\n" +
                        "        (i_units = 'Bunch' or i_units = 'Ton') and\n" +
                        "        (i_size = 'N/A' or i_size = 'small')\n" +
                        "        ) or\n" +
                        "        (i_category = 'Men' and\n" +
                        "        (i_color = 'floral' or i_color = 'deep') and\n" +
                        "        (i_units = 'N/A' or i_units = 'Dozen') and\n" +
                        "        (i_size = 'petite' or i_size = 'large')\n" +
                        "        ) or\n" +
                        "        (i_category = 'Men' and\n" +
                        "        (i_color = 'light' or i_color = 'cornflower') and\n" +
                        "        (i_units = 'Box' or i_units = 'Pound') and\n" +
                        "        (i_size = 'medium' or i_size = 'extra large')\n" +
                        "        ))) or\n" +
                        "       (i_manufact = i1.i_manufact and\n" +
                        "        ((i_category = 'Women' and \n" +
                        "        (i_color = 'midnight' or i_color = 'snow') and\n" +
                        "        (i_units = 'Pallet' or i_units = 'Gross') and\n" +
                        "        (i_size = 'medium' or i_size = 'extra large')\n" +
                        "        ) or\n" +
                        "        (i_category = 'Women' and\n" +
                        "        (i_color = 'cyan' or i_color = 'papaya') and\n" +
                        "        (i_units = 'Cup' or i_units = 'Dram') and\n" +
                        "        (i_size = 'N/A' or i_size = 'small')\n" +
                        "        ) or\n" +
                        "        (i_category = 'Men' and\n" +
                        "        (i_color = 'orange' or i_color = 'frosted') and\n" +
                        "        (i_units = 'Each' or i_units = 'Tbl') and\n" +
                        "        (i_size = 'petite' or i_size = 'large')\n" +
                        "        ) or\n" +
                        "        (i_category = 'Men' and\n" +
                        "        (i_color = 'forest' or i_color = 'ghost') and\n" +
                        "        (i_units = 'Lb' or i_units = 'Bundle') and\n" +
                        "        (i_size = 'medium' or i_size = 'extra large')\n" +
                        "        )))) > 0\n" +
                        " order by i_product_name\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS42",
                " select dt.d_year, item.i_category_id, item.i_category, sum(ss_ext_sales_price) sum_sales\n" +
                        " from \tdate_dim dt, store_sales, item\n" +
                        " where dt.d_date_sk = store_sales.ss_sold_date_sk\n" +
                        " \tand store_sales.ss_item_sk = item.i_item_sk\n" +
                        " \tand item.i_manager_id = 1\n" +
                        " \tand dt.d_moy=11\n" +
                        " \tand dt.d_year=2000\n" +
                        " group by \tdt.d_year\n" +
                        " \t\t,item.i_category_id\n" +
                        " \t\t,item.i_category\n" +
                        " order by       sum(ss_ext_sales_price) desc,dt.d_year\n" +
                        " \t\t,item.i_category_id\n" +
                        " \t\t,item.i_category\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS43",
                " select s_store_name, s_store_id,\n" +
                        "        sum(case when (d_day_name='Sunday') then ss_sales_price else null end) sun_sales,\n" +
                        "        sum(case when (d_day_name='Monday') then ss_sales_price else null end) mon_sales,\n" +
                        "        sum(case when (d_day_name='Tuesday') then ss_sales_price else  null end) tue_sales,\n" +
                        "        sum(case when (d_day_name='Wednesday') then ss_sales_price else null end) wed_sales,\n" +
                        "        sum(case when (d_day_name='Thursday') then ss_sales_price else null end) thu_sales,\n" +
                        "        sum(case when (d_day_name='Friday') then ss_sales_price else null end) fri_sales,\n" +
                        "        sum(case when (d_day_name='Saturday') then ss_sales_price else null end) sat_sales\n" +
                        " from date_dim, store_sales, store\n" +
                        " where d_date_sk = ss_sold_date_sk and\n" +
                        "       s_store_sk = ss_store_sk and\n" +
                        "       s_gmt_offset = -5 and\n" +
                        "       d_year = 2000\n" +
                        " group by s_store_name, s_store_id\n" +
                        " order by s_store_name, s_store_id,sun_sales,mon_sales,tue_sales,wed_sales,\n" +
                        "          thu_sales,fri_sales,sat_sales\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS44",
                " select asceding.rnk, i1.i_product_name best_performing, i2.i_product_name worst_performing\n" +
                        " from(select *\n" +
                        "     from (select item_sk,rank() over (order by rank_col asc) rnk\n" +
                        "           from (select ss_item_sk item_sk,avg(ss_net_profit) rank_col\n" +
                        "                 from store_sales ss1\n" +
                        "                 where ss_store_sk = 4\n" +
                        "                 group by ss_item_sk\n" +
                        "                 having avg(ss_net_profit) > 0.9*(select avg(ss_net_profit) rank_col\n" +
                        "                                                  from store_sales\n" +
                        "                                                  where ss_store_sk = 4\n" +
                        "                                                    and ss_addr_sk is null\n" +
                        "                                                  group by ss_store_sk))V1)V11\n" +
                        "     where rnk  < 11) asceding,\n" +
                        "    (select *\n" +
                        "     from (select item_sk,rank() over (order by rank_col desc) rnk\n" +
                        "           from (select ss_item_sk item_sk,avg(ss_net_profit) rank_col\n" +
                        "                 from store_sales ss1\n" +
                        "                 where ss_store_sk = 4\n" +
                        "                 group by ss_item_sk\n" +
                        "                 having avg(ss_net_profit) > 0.9*(select avg(ss_net_profit) rank_col\n" +
                        "                                                  from store_sales\n" +
                        "                                                  where ss_store_sk = 4\n" +
                        "                                                    and ss_addr_sk is null\n" +
                        "                                                  group by ss_store_sk))V2)V21\n" +
                        "     where rnk  < 11) descending,\n" +
                        " item i1, item i2\n" +
                        " where asceding.rnk = descending.rnk\n" +
                        "   and i1.i_item_sk=asceding.item_sk\n" +
                        "   and i2.i_item_sk=descending.item_sk\n" +
                        " order by asceding.rnk\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS45",
                " select ca_zip, ca_city, sum(ws_sales_price) sum_sales\n" +
                        " from web_sales, customer, customer_address, date_dim, item\n" +
                        " where ws_bill_customer_sk = c_customer_sk\n" +
                        " \tand c_current_addr_sk = ca_address_sk\n" +
                        " \tand ws_item_sk = i_item_sk\n" +
                        " \tand ( substr(ca_zip,1,5) in ('85669', '86197','88274','83405','86475', '85392', '85460', '80348', '81792')\n" +
                        " \t      or\n" +
                        " \t      i_item_id in (select i_item_id\n" +
                        "                             from item\n" +
                        "                             where i_item_sk in (2, 3, 5, 7, 11, 13, 17, 19, 23, 29)\n" +
                        "                             )\n" +
                        " \t    )\n" +
                        " \tand ws_sold_date_sk = d_date_sk\n" +
                        " \tand d_qoy = 2 and d_year = 2001\n" +
                        " group by ca_zip, ca_city\n" +
                        " order by ca_zip, ca_city\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS46",
                "from\n" +
                        "   (select ss_ticket_number\n" +
                        "          ,ss_customer_sk\n" +
                        "          ,ca_city bought_city\n" +
                        "          ,sum(ss_coupon_amt) amt\n" +
                        "          ,sum(ss_net_profit) profit\n" +
                        "    from store_sales, date_dim, store, household_demographics, customer_address\n" +
                        "    where store_sales.ss_sold_date_sk = date_dim.d_date_sk\n" +
                        "    and store_sales.ss_store_sk = store.s_store_sk\n" +
                        "    and store_sales.ss_hdemo_sk = household_demographics.hd_demo_sk\n" +
                        "    and store_sales.ss_addr_sk = customer_address.ca_address_sk\n" +
                        "    and (household_demographics.hd_dep_count = 4 or\n" +
                        "         household_demographics.hd_vehicle_count= 3)\n" +
                        "    and date_dim.d_dow in (6,0)\n" +
                        "    and date_dim.d_year in (1999,1999+1,1999+2)\n" +
                        "    and store.s_city in ('Fairview','Midway','Fairview','Fairview','Fairview') \n" +
                        "    group by ss_ticket_number,ss_customer_sk,ss_addr_sk,ca_city) dn,customer,customer_address current_addr\n" +
                        "    where ss_customer_sk = c_customer_sk\n" +
                        "      and customer.c_current_addr_sk = current_addr.ca_address_sk\n" +
                        "      and current_addr.ca_city <> bought_city\n" +
                        "  order by c_last_name, c_first_name, ca_city, bought_city, ss_ticket_number\n" +
                        "  limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS47",
                " with v1 as(\n" +
                        " select i_category, i_brand,\n" +
                        "        s_store_name, s_company_name,\n" +
                        "        d_year, d_moy,\n" +
                        "        sum(ss_sales_price) sum_sales,\n" +
                        "        avg(sum(ss_sales_price)) over\n" +
                        "          (partition by i_category, i_brand,\n" +
                        "                     s_store_name, s_company_name, d_year)\n" +
                        "          avg_monthly_sales,\n" +
                        "        rank() over\n" +
                        "          (partition by i_category, i_brand,\n" +
                        "                     s_store_name, s_company_name\n" +
                        "           order by d_year, d_moy) rn\n" +
                        " from item, store_sales, date_dim, store\n" +
                        " where ss_item_sk = i_item_sk and\n" +
                        "       ss_sold_date_sk = d_date_sk and\n" +
                        "       ss_store_sk = s_store_sk and\n" +
                        "       (\n" +
                        "         d_year = 1999 or\n" +
                        "         ( d_year = 1999-1 and d_moy =12) or\n" +
                        "         ( d_year = 1999+1 and d_moy =1)\n" +
                        "       )\n" +
                        " group by i_category, i_brand,\n" +
                        "          s_store_name, s_company_name,\n" +
                        "          d_year, d_moy),\n" +
                        " v2 as(\n" +
                        " select v1.i_category, v1.i_brand, v1.s_store_name, v1.s_company_name, v1.d_year, \n" +
                        "                     v1.d_moy, v1.avg_monthly_sales ,v1.sum_sales, v1_lag.sum_sales psum, \n" +
                        "                     v1_lead.sum_sales nsum\n" +
                        " from v1, v1 v1_lag, v1 v1_lead\n" +
                        " where v1.i_category = v1_lag.i_category and\n" +
                        "       v1.i_category = v1_lead.i_category and\n" +
                        "       v1.i_brand = v1_lag.i_brand and\n" +
                        "       v1.i_brand = v1_lead.i_brand and\n" +
                        "       v1.s_store_name = v1_lag.s_store_name and\n" +
                        "       v1.s_store_name = v1_lead.s_store_name and\n" +
                        "       v1.s_company_name = v1_lag.s_company_name and\n" +
                        "       v1.s_company_name = v1_lead.s_company_name and\n" +
                        "       v1.rn = v1_lag.rn + 1 and\n" +
                        "       v1.rn = v1_lead.rn - 1)\n" +
                        " select * from v2\n" +
                        " where  d_year = 1999 and\n" +
                        "        avg_monthly_sales > 0 and\n" +
                        "        case when avg_monthly_sales > 0 then abs(sum_sales - avg_monthly_sales) / avg_monthly_sales else null end > 0.1\n" +
                        " order by sum_sales - avg_monthly_sales, 3\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS48",
                "select sum (ss_quantity) sum_quan\n" +
                        " from store_sales, store, customer_demographics, customer_address, date_dim\n" +
                        " where s_store_sk = ss_store_sk\n" +
                        " and  ss_sold_date_sk = d_date_sk and d_year = 2000\n" +
                        " and\n" +
                        " (\n" +
                        "  (\n" +
                        "   cd_demo_sk = ss_cdemo_sk\n" +
                        "   and\n" +
                        "   cd_marital_status = 'M'\n" +
                        "   and \n" +
                        "   cd_education_status = '4 yr Degree'\n" +
                        "   and\n" +
                        "   ss_sales_price between 100.00 and 150.00\n" +
                        "   )\n" +
                        " or\n" +
                        "  (\n" +
                        "  cd_demo_sk = ss_cdemo_sk\n" +
                        "   and\n" +
                        "   cd_marital_status = 'D'\n" +
                        "   and \n" +
                        "   cd_education_status = '2 yr Degree'\n" +
                        "   and\n" +
                        "   ss_sales_price between 50.00 and 100.00\n" +
                        "  )\n" +
                        " or\n" +
                        " (\n" +
                        "  cd_demo_sk = ss_cdemo_sk\n" +
                        "   and\n" +
                        "   cd_marital_status = 'S'\n" +
                        "   and \n" +
                        "   cd_education_status = 'College'\n" +
                        "   and\n" +
                        "   ss_sales_price between 150.00 and 200.00\n" +
                        " )\n" +
                        " )\n" +
                        " and\n" +
                        " (\n" +
                        "  (\n" +
                        "  ss_addr_sk = ca_address_sk\n" +
                        "  and\n" +
                        "  ca_country = 'United States'\n" +
                        "  and\n" +
                        "  ca_state in ('CO', 'OH', 'TX')\n" +
                        "  and ss_net_profit between 0 and 2000\n" +
                        "  )\n" +
                        " or\n" +
                        "  (ss_addr_sk = ca_address_sk\n" +
                        "  and\n" +
                        "  ca_country = 'United States'\n" +
                        "  and\n" +
                        "  ca_state in ('OR', 'MN', 'KY')\n" +
                        "  and ss_net_profit between 150 and 3000\n" +
                        "  )\n" +
                        " or\n" +
                        "  (ss_addr_sk = ca_address_sk\n" +
                        "  and\n" +
                        "  ca_country = 'United States'\n" +
                        "  and\n" +
                        "  ca_state in ('VA', 'CA', 'MS')\n" +
                        "  and ss_net_profit between 50 and 25000\n" +
                        "  )\n" +
                        " )"
        );
        tpcdsSQLMap.put(
                "TPCDS49",
                " select 'web' as channel, web.item, web.return_ratio, web.return_rank, web.currency_rank\n" +
                        " from (\n" +
                        " \tselect\n" +
                        "    item, return_ratio, currency_ratio,\n" +
                        " \t  rank() over (order by return_ratio) as return_rank,\n" +
                        " \t  rank() over (order by currency_ratio) as currency_rank\n" +
                        " \tfrom\n" +
                        " \t(\tselect ws.ws_item_sk as item\n" +
                        " \t\t,(cast(sum(coalesce(wr.wr_return_quantity,0)) as decimal(15,4))/\n" +
                        " \t\tcast(sum(coalesce(ws.ws_quantity,0)) as decimal(15,4) )) as return_ratio\n" +
                        " \t\t,(cast(sum(coalesce(wr.wr_return_amt,0)) as decimal(15,4))/\n" +
                        " \t\tcast(sum(coalesce(ws.ws_net_paid,0)) as decimal(15,4) )) as currency_ratio\n" +
                        " \t\tfrom\n" +
                        " \t\t web_sales ws left outer join web_returns wr\n" +
                        " \t\t\ton (ws.ws_order_number = wr.wr_order_number and\n" +
                        " \t\t\tws.ws_item_sk = wr.wr_item_sk)\n" +
                        "        ,date_dim\n" +
                        " \t\twhere\n" +
                        " \t\t\twr.wr_return_amt > 10000\n" +
                        " \t\t\tand ws.ws_net_profit > 1\n" +
                        "                         and ws.ws_net_paid > 0\n" +
                        "                         and ws.ws_quantity > 0\n" +
                        "                         and ws_sold_date_sk = d_date_sk\n" +
                        "                         and d_year = 2001\n" +
                        "                         and d_moy = 12\n" +
                        " \t\tgroup by ws.ws_item_sk\n" +
                        " \t) in_web\n" +
                        " ) web\n" +
                        " where (web.return_rank <= 10 or web.currency_rank <= 10)\n" +
                        " union\n" +
                        " select\n" +
                        "    'catalog' as channel, catalog.item, catalog.return_ratio,\n" +
                        "    catalog.return_rank, catalog.currency_rank\n" +
                        " from (\n" +
                        " \tselect\n" +
                        "    item, return_ratio, currency_ratio,\n" +
                        " \t  rank() over (order by return_ratio) as return_rank,\n" +
                        " \t  rank() over (order by currency_ratio) as currency_rank\n" +
                        " \tfrom\n" +
                        " \t(\tselect\n" +
                        " \t\tcs.cs_item_sk as item\n" +
                        " \t\t,(cast(sum(coalesce(cr.cr_return_quantity,0)) as decimal(15,4))/\n" +
                        " \t\tcast(sum(coalesce(cs.cs_quantity,0)) as decimal(15,4) )) as return_ratio\n" +
                        " \t\t,(cast(sum(coalesce(cr.cr_return_amount,0)) as decimal(15,4))/\n" +
                        " \t\tcast(sum(coalesce(cs.cs_net_paid,0)) as decimal(15,4) )) as currency_ratio\n" +
                        " \t\tfrom\n" +
                        " \t\tcatalog_sales cs left outer join catalog_returns cr\n" +
                        " \t\t\ton (cs.cs_order_number = cr.cr_order_number and\n" +
                        " \t\t\tcs.cs_item_sk = cr.cr_item_sk)\n" +
                        "                ,date_dim\n" +
                        " \t\twhere\n" +
                        " \t\t\tcr.cr_return_amount > 10000\n" +
                        " \t\t\tand cs.cs_net_profit > 1\n" +
                        "                         and cs.cs_net_paid > 0\n" +
                        "                         and cs.cs_quantity > 0\n" +
                        "                         and cs_sold_date_sk = d_date_sk\n" +
                        "                         and d_year = 2001\n" +
                        "                         and d_moy = 12\n" +
                        "                 group by cs.cs_item_sk\n" +
                        " \t) in_cat\n" +
                        " ) catalog\n" +
                        " where (catalog.return_rank <= 10 or catalog.currency_rank <=10)\n" +
                        " union\n" +
                        " select\n" +
                        "    'store' as channel, store.item, store.return_ratio,\n" +
                        "    store.return_rank, store.currency_rank\n" +
                        " from (\n" +
                        " \tselect\n" +
                        "      item, return_ratio, currency_ratio,\n" +
                        " \t    rank() over (order by return_ratio) as return_rank,\n" +
                        " \t    rank() over (order by currency_ratio) as currency_rank\n" +
                        " \tfrom\n" +
                        " \t(\tselect sts.ss_item_sk as item\n" +
                        " \t\t,(cast(sum(coalesce(sr.sr_return_quantity,0)) as decimal(15,4))/\n" +
                        "               cast(sum(coalesce(sts.ss_quantity,0)) as decimal(15,4) )) as return_ratio\n" +
                        " \t\t,(cast(sum(coalesce(sr.sr_return_amt,0)) as decimal(15,4))/\n" +
                        "               cast(sum(coalesce(sts.ss_net_paid,0)) as decimal(15,4) )) as currency_ratio\n" +
                        " \t\tfrom\n" +
                        " \t\tstore_sales sts left outer join store_returns sr\n" +
                        " \t\t\ton (sts.ss_ticket_number = sr.sr_ticket_number and sts.ss_item_sk = sr.sr_item_sk)\n" +
                        "                ,date_dim\n" +
                        " \t\twhere\n" +
                        " \t\t\tsr.sr_return_amt > 10000\n" +
                        " \t\t\tand sts.ss_net_profit > 1\n" +
                        "                         and sts.ss_net_paid > 0\n" +
                        "                         and sts.ss_quantity > 0\n" +
                        "                         and ss_sold_date_sk = d_date_sk\n" +
                        "                         and d_year = 2001\n" +
                        "                         and d_moy = 12\n" +
                        " \t\tgroup by sts.ss_item_sk\n" +
                        " \t) in_store\n" +
                        " ) store\n" +
                        " where (store.return_rank <= 10 or store.currency_rank <= 10)\n" +
                        " order by 1,4,5\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS50",
                " select\n" +
                        "    s_store_name, s_company_id, s_street_number, s_street_name, s_street_type,\n" +
                        "    s_suite_number, s_city, s_county, s_state, s_zip\n" +
                        "   ,sum(case when (sr_returned_date_sk - ss_sold_date_sk <= 30 ) then 1 else 0 end)  as days0\n" +
                        "   ,sum(case when (sr_returned_date_sk - ss_sold_date_sk > 30) and\n" +
                        "                  (sr_returned_date_sk - ss_sold_date_sk <= 60) then 1 else 0 end )  as days31\n" +
                        "   ,sum(case when (sr_returned_date_sk - ss_sold_date_sk > 60) and\n" +
                        "                  (sr_returned_date_sk - ss_sold_date_sk <= 90) then 1 else 0 end)  as days61\n" +
                        "   ,sum(case when (sr_returned_date_sk - ss_sold_date_sk > 90) and\n" +
                        "                  (sr_returned_date_sk - ss_sold_date_sk <= 120) then 1 else 0 end)  as days91\n" +
                        "   ,sum(case when (sr_returned_date_sk - ss_sold_date_sk  > 120) then 1 else 0 end)  as days120\n" +
                        " from\n" +
                        "    store_sales, store_returns, store, date_dim d1, date_dim d2\n" +
                        " where\n" +
                        "     d2.d_year = 2001\n" +
                        " and d2.d_moy  = 8\n" +
                        " and ss_ticket_number = sr_ticket_number\n" +
                        " and ss_item_sk = sr_item_sk\n" +
                        " and ss_sold_date_sk   = d1.d_date_sk\n" +
                        " and sr_returned_date_sk   = d2.d_date_sk\n" +
                        " and ss_customer_sk = sr_customer_sk\n" +
                        " and ss_store_sk = s_store_sk\n" +
                        " group by\n" +
                        "     s_store_name, s_company_id, s_street_number, s_street_name, s_street_type,\n" +
                        "     s_suite_number, s_city, s_county, s_state, s_zip\n" +
                        "  order by\n" +
                        "     s_store_name, s_company_id, s_street_number, s_street_name, s_street_type,\n" +
                        "     s_suite_number, s_city, s_county, s_state, s_zip\n" +
                        "  limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS51",
                " WITH web_v1 as (\n" +
                        " select\n" +
                        "   ws_item_sk item_sk, d_date,\n" +
                        "   sum(sum(ws_sales_price))\n" +
                        "       over (partition by ws_item_sk order by d_date rows between unbounded preceding and current row) cume_sales\n" +
                        " from web_sales, date_dim\n" +
                        " where ws_sold_date_sk=d_date_sk\n" +
                        "   and d_month_seq between 1200 and 1200+11\n" +
                        "   and ws_item_sk is not NULL\n" +
                        " group by ws_item_sk, d_date),\n" +
                        " store_v1 as (\n" +
                        " select\n" +
                        "   ss_item_sk item_sk, d_date,\n" +
                        "   sum(sum(ss_sales_price))\n" +
                        "       over (partition by ss_item_sk order by d_date rows between unbounded preceding and current row) cume_sales\n" +
                        " from store_sales, date_dim\n" +
                        " where ss_sold_date_sk=d_date_sk\n" +
                        "   and d_month_seq between 1200 and 1200+11\n" +
                        "   and ss_item_sk is not NULL\n" +
                        " group by ss_item_sk, d_date)\n" +
                        " select *\n" +
                        " from (select item_sk, d_date, web_sales, store_sales\n" +
                        "      ,max(web_sales)\n" +
                        "          over (partition by item_sk order by d_date rows between unbounded preceding and current row) web_cumulative\n" +
                        "      ,max(store_sales)\n" +
                        "          over (partition by item_sk order by d_date rows between unbounded preceding and current row) store_cumulative\n" +
                        "      from (select case when web.item_sk is not null then web.item_sk else store.item_sk end item_sk\n" +
                        "                  ,case when web.d_date is not null then web.d_date else store.d_date end d_date\n" +
                        "                  ,web.cume_sales web_sales\n" +
                        "                  ,store.cume_sales store_sales\n" +
                        "            from web_v1 web full outer join store_v1 store on (web.item_sk = store.item_sk\n" +
                        "                                                           and web.d_date = store.d_date)\n" +
                        "           )x )y\n" +
                        " where web_cumulative > store_cumulative\n" +
                        " order by item_sk, d_date\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS52",
                "select dt.d_year\n" +
                        " \t,item.i_brand_id brand_id\n" +
                        " \t,item.i_brand brand\n" +
                        " \t,sum(ss_ext_sales_price) ext_price\n" +
                        " from date_dim dt, store_sales, item\n" +
                        " where dt.d_date_sk = store_sales.ss_sold_date_sk\n" +
                        "    and store_sales.ss_item_sk = item.i_item_sk\n" +
                        "    and item.i_manager_id = 1\n" +
                        "    and dt.d_moy=11\n" +
                        "    and dt.d_year=2000\n" +
                        " group by dt.d_year, item.i_brand, item.i_brand_id\n" +
                        " order by dt.d_year, ext_price desc, brand_id\n" +
                        "limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS53",
                " select * from\n" +
                        "   (select i_manufact_id,\n" +
                        "           sum(ss_sales_price) sum_sales,\n" +
                        "           avg(sum(ss_sales_price)) over (partition by i_manufact_id) avg_quarterly_sales\n" +
                        "     from item, store_sales, date_dim, store\n" +
                        "     where ss_item_sk = i_item_sk and\n" +
                        "           ss_sold_date_sk = d_date_sk and\n" +
                        "           ss_store_sk = s_store_sk and\n" +
                        "           d_month_seq in (1200,1200+1,1200+2,1200+3,1200+4,1200+5,1200+6,\n" +
                        "                           1200+7,1200+8,1200+9,1200+10,1200+11) and\n" +
                        "     ((i_category in ('Books','Children','Electronics') and\n" +
                        "       i_class in ('personal','portable','reference','self-help') and\n" +
                        "       i_brand in ('scholaramalgamalg #14','scholaramalgamalg #7',\n" +
                        " \t\t  'exportiunivamalg #9','scholaramalgamalg #9'))\n" +
                        "     or\n" +
                        "     (i_category in ('Women','Music','Men') and\n" +
                        "      i_class in ('accessories','classical','fragrances','pants') and\n" +
                        "      i_brand in ('amalgimporto #1','edu packscholar #1','exportiimporto #1',\n" +
                        " \t\t'importoamalg #1')))\n" +
                        "     group by i_manufact_id, d_qoy ) tmp1\n" +
                        " where case when avg_quarterly_sales > 0\n" +
                        " \tthen abs (sum_sales - avg_quarterly_sales)/ avg_quarterly_sales\n" +
                        " \telse null end > 0.1\n" +
                        " order by avg_quarterly_sales,\n" +
                        "  \t sum_sales,\n" +
                        " \t i_manufact_id\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS54",
                "with my_customers as (\n" +
                        " select distinct c_customer_sk\n" +
                        "        , c_current_addr_sk\n" +
                        " from\n" +
                        "        ( select cs_sold_date_sk sold_date_sk,\n" +
                        "                 cs_bill_customer_sk customer_sk,\n" +
                        "                 cs_item_sk item_sk\n" +
                        "          from   catalog_sales\n" +
                        "          union all\n" +
                        "          select ws_sold_date_sk sold_date_sk,\n" +
                        "                 ws_bill_customer_sk customer_sk,\n" +
                        "                 ws_item_sk item_sk\n" +
                        "          from   web_sales\n" +
                        "         ) cs_or_ws_sales,\n" +
                        "         item,\n" +
                        "         date_dim,\n" +
                        "         customer\n" +
                        " where   sold_date_sk = d_date_sk\n" +
                        "         and item_sk = i_item_sk\n" +
                        "         and i_category = 'Women'\n" +
                        "         and i_class = 'maternity'\n" +
                        "         and c_customer_sk = cs_or_ws_sales.customer_sk\n" +
                        "         and d_moy = 12\n" +
                        "         and d_year = 1998\n" +
                        " )\n" +
                        " , my_revenue as (\n" +
                        " select c_customer_sk,\n" +
                        "        sum(ss_ext_sales_price) as revenue\n" +
                        " from   my_customers,\n" +
                        "        store_sales,\n" +
                        "        customer_address,\n" +
                        "        store,\n" +
                        "        date_dim\n" +
                        " where  c_current_addr_sk = ca_address_sk\n" +
                        "        and ca_county = s_county\n" +
                        "        and ca_state = s_state\n" +
                        "        and ss_sold_date_sk = d_date_sk\n" +
                        "        and c_customer_sk = ss_customer_sk\n" +
                        "        and d_month_seq between (select distinct d_month_seq+1\n" +
                        "                                 from   date_dim where d_year = 1998 and d_moy = 12)\n" +
                        "                           and  (select distinct d_month_seq+3\n" +
                        "                                 from   date_dim where d_year = 1998 and d_moy = 12)\n" +
                        " group by c_customer_sk\n" +
                        " )\n" +
                        " , segments as\n" +
                        " (select cast((revenue/50) as integer) as segment from my_revenue)\n" +
                        " select segment, count(*) as num_customers, segment*50 as segment_base\n" +
                        " from segments\n" +
                        " group by segment\n" +
                        " order by segment, num_customers\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS55",
                "select i_brand_id brand_id, i_brand brand,\n" +
                        " \tsum(ss_ext_sales_price) ext_price\n" +
                        " from date_dim, store_sales, item\n" +
                        " where d_date_sk = ss_sold_date_sk\n" +
                        " \tand ss_item_sk = i_item_sk\n" +
                        " \tand i_manager_id=28\n" +
                        " \tand d_moy=11\n" +
                        " \tand d_year=1999\n" +
                        " group by i_brand, i_brand_id\n" +
                        " order by ext_price desc, brand_id\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS56",
                " with ss as (\n" +
                        " select i_item_id,sum(ss_ext_sales_price) total_sales\n" +
                        " from\n" +
                        " \t  store_sales, date_dim, customer_address, item\n" +
                        " where\n" +
                        "    i_item_id in (select i_item_id from item where i_color in ('slate','blanched','burnished'))\n" +
                        " and     ss_item_sk              = i_item_sk\n" +
                        " and     ss_sold_date_sk         = d_date_sk\n" +
                        " and     d_year                  = 2001\n" +
                        " and     d_moy                   = 2\n" +
                        " and     ss_addr_sk              = ca_address_sk\n" +
                        " and     ca_gmt_offset           = -5\n" +
                        " group by i_item_id),\n" +
                        " cs as (\n" +
                        " select i_item_id,sum(cs_ext_sales_price) total_sales\n" +
                        " from\n" +
                        " \t  catalog_sales, date_dim, customer_address, item\n" +
                        " where\n" +
                        "    i_item_id in (select i_item_id from item where i_color in ('slate','blanched','burnished'))\n" +
                        " and     cs_item_sk              = i_item_sk\n" +
                        " and     cs_sold_date_sk         = d_date_sk\n" +
                        " and     d_year                  = 2001\n" +
                        " and     d_moy                   = 2\n" +
                        " and     cs_bill_addr_sk         = ca_address_sk\n" +
                        " and     ca_gmt_offset           = -5\n" +
                        " group by i_item_id),\n" +
                        " ws as (\n" +
                        " select i_item_id,sum(ws_ext_sales_price) total_sales\n" +
                        " from\n" +
                        " \t  web_sales, date_dim, customer_address, item\n" +
                        " where\n" +
                        "    i_item_id in (select i_item_id from item where i_color in ('slate','blanched','burnished'))\n" +
                        " and     ws_item_sk              = i_item_sk\n" +
                        " and     ws_sold_date_sk         = d_date_sk\n" +
                        " and     d_year                  = 2001 \n" +
                        " and     d_moy                   = 2\n" +
                        " and     ws_bill_addr_sk         = ca_address_sk\n" +
                        " and     ca_gmt_offset           = -5\n" +
                        " group by i_item_id)\n" +
                        " select i_item_id ,sum(total_sales) total_sales\n" +
                        " from  (select * from ss\n" +
                        "        union all\n" +
                        "        select * from cs\n" +
                        "        union all\n" +
                        "        select * from ws) tmp1\n" +
                        " group by i_item_id\n" +
                        " order by total_sales\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS57",
                " with v1 as(\n" +
                        " select i_category, i_brand,\n" +
                        "        cc_name,\n" +
                        "        d_year, d_moy,\n" +
                        "        sum(cs_sales_price) sum_sales,\n" +
                        "        avg(sum(cs_sales_price)) over\n" +
                        "          (partition by i_category, i_brand, cc_name, d_year)\n" +
                        "          avg_monthly_sales,\n" +
                        "        rank() over\n" +
                        "          (partition by i_category, i_brand, cc_name\n" +
                        "           order by d_year, d_moy) rn\n" +
                        " from item, catalog_sales, date_dim, call_center\n" +
                        " where cs_item_sk = i_item_sk and\n" +
                        "       cs_sold_date_sk = d_date_sk and\n" +
                        "       cc_call_center_sk= cs_call_center_sk and\n" +
                        "       (\n" +
                        "         d_year = 1999 or\n" +
                        "         ( d_year = 1999-1 and d_moy =12) or\n" +
                        "         ( d_year = 1999+1 and d_moy =1)\n" +
                        "       )\n" +
                        " group by i_category, i_brand,\n" +
                        "          cc_name , d_year, d_moy),\n" +
                        " v2 as(\n" +
                        " select v1.i_category, v1.i_brand, v1.cc_name, v1.d_year, v1.d_moy\n" +
                        "        ,v1.avg_monthly_sales\n" +
                        "        ,v1.sum_sales, v1_lag.sum_sales psum, v1_lead.sum_sales nsum\n" +
                        " from v1, v1 v1_lag, v1 v1_lead\n" +
                        " where v1.i_category = v1_lag.i_category and\n" +
                        "       v1.i_category = v1_lead.i_category and\n" +
                        "       v1.i_brand = v1_lag.i_brand and\n" +
                        "       v1.i_brand = v1_lead.i_brand and\n" +
                        "       v1. cc_name = v1_lag. cc_name and\n" +
                        "       v1. cc_name = v1_lead. cc_name and\n" +
                        "       v1.rn = v1_lag.rn + 1 and\n" +
                        "       v1.rn = v1_lead.rn - 1)\n" +
                        " select * from v2\n" +
                        " where  d_year = 1999 and\n" +
                        "        avg_monthly_sales > 0 and\n" +
                        "        case when avg_monthly_sales > 0 then abs(sum_sales - avg_monthly_sales) / avg_monthly_sales else null end > 0.1\n" +
                        " order by sum_sales - avg_monthly_sales, 3\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS58",
                " with ss_items as\n" +
                        " (select i_item_id item_id, sum(ss_ext_sales_price) ss_item_rev\n" +
                        " from store_sales, item, date_dim\n" +
                        " where ss_item_sk = i_item_sk\n" +
                        "   and d_date in (select d_date\n" +
                        "                  from date_dim\n" +
                        "                  where d_week_seq = (select d_week_seq\n" +
                        "                                      from date_dim\n" +
                        "                                      where d_date = cast('2000-01-03' as date)))\n" +
                        "   and ss_sold_date_sk   = d_date_sk\n" +
                        " group by i_item_id),\n" +
                        " cs_items as\n" +
                        " (select i_item_id item_id\n" +
                        "        ,sum(cs_ext_sales_price) cs_item_rev\n" +
                        "  from catalog_sales, item, date_dim\n" +
                        " where cs_item_sk = i_item_sk\n" +
                        "  and  d_date in (select d_date\n" +
                        "                  from date_dim\n" +
                        "                  where d_week_seq = (select d_week_seq\n" +
                        "                                      from date_dim\n" +
                        "                                      where d_date = cast('2000-01-03' as date)))\n" +
                        "  and  cs_sold_date_sk = d_date_sk\n" +
                        " group by i_item_id),\n" +
                        " ws_items as\n" +
                        " (select i_item_id item_id, sum(ws_ext_sales_price) ws_item_rev\n" +
                        "  from web_sales, item, date_dim\n" +
                        " where ws_item_sk = i_item_sk\n" +
                        "  and  d_date in (select d_date\n" +
                        "                  from date_dim\n" +
                        "                  where d_week_seq =(select d_week_seq\n" +
                        "                                     from date_dim\n" +
                        "                                     where d_date = cast('2000-01-03' as date)))\n" +
                        "  and ws_sold_date_sk   = d_date_sk\n" +
                        " group by i_item_id)\n" +
                        " select ss_items.item_id\n" +
                        "       ,ss_item_rev\n" +
                        "       ,ss_item_rev/(ss_item_rev+cs_item_rev+ws_item_rev)/3 * 100 ss_dev\n" +
                        "       ,cs_item_rev\n" +
                        "       ,cs_item_rev/(ss_item_rev+cs_item_rev+ws_item_rev)/3 * 100 cs_dev\n" +
                        "       ,ws_item_rev\n" +
                        "       ,ws_item_rev/(ss_item_rev+cs_item_rev+ws_item_rev)/3 * 100 ws_dev\n" +
                        "       ,(ss_item_rev+cs_item_rev+ws_item_rev)/3 average\n" +
                        " from ss_items,cs_items,ws_items\n" +
                        " where ss_items.item_id=cs_items.item_id\n" +
                        "   and ss_items.item_id=ws_items.item_id\n" +
                        "   and ss_item_rev between 0.9 * cs_item_rev and 1.1 * cs_item_rev\n" +
                        "   and ss_item_rev between 0.9 * ws_item_rev and 1.1 * ws_item_rev\n" +
                        "   and cs_item_rev between 0.9 * ss_item_rev and 1.1 * ss_item_rev\n" +
                        "   and cs_item_rev between 0.9 * ws_item_rev and 1.1 * ws_item_rev\n" +
                        "   and ws_item_rev between 0.9 * ss_item_rev and 1.1 * ss_item_rev\n" +
                        "   and ws_item_rev between 0.9 * cs_item_rev and 1.1 * cs_item_rev\n" +
                        " order by ss_items.item_id, ss_item_rev\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS59",
                "with wss as\n" +
                        " (select d_week_seq,\n" +
                        "        ss_store_sk,\n" +
                        "        sum(case when (d_day_name='Sunday') then ss_sales_price else null end) sun_sales,\n" +
                        "        sum(case when (d_day_name='Monday') then ss_sales_price else null end) mon_sales,\n" +
                        "        sum(case when (d_day_name='Tuesday') then ss_sales_price else  null end) tue_sales,\n" +
                        "        sum(case when (d_day_name='Wednesday') then ss_sales_price else null end) wed_sales,\n" +
                        "        sum(case when (d_day_name='Thursday') then ss_sales_price else null end) thu_sales,\n" +
                        "        sum(case when (d_day_name='Friday') then ss_sales_price else null end) fri_sales,\n" +
                        "        sum(case when (d_day_name='Saturday') then ss_sales_price else null end) sat_sales\n" +
                        " from store_sales,date_dim\n" +
                        " where d_date_sk = ss_sold_date_sk\n" +
                        " group by d_week_seq,ss_store_sk\n" +
                        " )\n" +
                        " select  s_store_name1,s_store_id1,d_week_seq1\n" +
                        "       ,sun_sales1/sun_sales2,mon_sales1/mon_sales2\n" +
                        "       ,tue_sales1/tue_sales2,wed_sales1/wed_sales2,thu_sales1/thu_sales2\n" +
                        "       ,fri_sales1/fri_sales2,sat_sales1/sat_sales2\n" +
                        " from\n" +
                        " (select s_store_name s_store_name1,wss.d_week_seq d_week_seq1\n" +
                        "        ,s_store_id s_store_id1,sun_sales sun_sales1\n" +
                        "        ,mon_sales mon_sales1,tue_sales tue_sales1\n" +
                        "        ,wed_sales wed_sales1,thu_sales thu_sales1\n" +
                        "        ,fri_sales fri_sales1,sat_sales sat_sales1\n" +
                        "  from wss,store,date_dim d\n" +
                        "  where d.d_week_seq = wss.d_week_seq and\n" +
                        "        ss_store_sk = s_store_sk and\n" +
                        "        d_month_seq between 1212 and 1212 + 11) y,\n" +
                        " (select s_store_name s_store_name2,wss.d_week_seq d_week_seq2\n" +
                        "        ,s_store_id s_store_id2,sun_sales sun_sales2\n" +
                        "        ,mon_sales mon_sales2,tue_sales tue_sales2\n" +
                        "        ,wed_sales wed_sales2,thu_sales thu_sales2\n" +
                        "        ,fri_sales fri_sales2,sat_sales sat_sales2\n" +
                        "  from wss,store,date_dim d\n" +
                        "  where d.d_week_seq = wss.d_week_seq and\n" +
                        "        ss_store_sk = s_store_sk and\n" +
                        "        d_month_seq between 1212+ 12 and 1212 + 23) x\n" +
                        " where s_store_id1=s_store_id2\n" +
                        "   and d_week_seq1=d_week_seq2-52\n" +
                        " order by s_store_name1,s_store_id1,d_week_seq1\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put(
                "TPCDS60",
                " with ss as (\n" +
                        "    select i_item_id,sum(ss_ext_sales_price) total_sales\n" +
                        "    from store_sales, date_dim, customer_address, item\n" +
                        "    where\n" +
                        "        i_item_id in (select i_item_id from item where i_category in ('Music'))\n" +
                        "    and     ss_item_sk              = i_item_sk\n" +
                        "    and     ss_sold_date_sk         = d_date_sk\n" +
                        "    and     d_year                  = 1998\n" +
                        "    and     d_moy                   = 9\n" +
                        "    and     ss_addr_sk              = ca_address_sk\n" +
                        "    and     ca_gmt_offset           = -5\n" +
                        "    group by i_item_id),\n" +
                        "  cs as (\n" +
                        "    select i_item_id,sum(cs_ext_sales_price) total_sales\n" +
                        "    from catalog_sales, date_dim, customer_address, item\n" +
                        "    where\n" +
                        "        i_item_id in (select i_item_id from item where i_category in ('Music'))\n" +
                        "    and     cs_item_sk              = i_item_sk\n" +
                        "    and     cs_sold_date_sk         = d_date_sk\n" +
                        "    and     d_year                  = 1998\n" +
                        "    and     d_moy                   = 9\n" +
                        "    and     cs_bill_addr_sk         = ca_address_sk\n" +
                        "    and     ca_gmt_offset           = -5\n" +
                        "    group by i_item_id),\n" +
                        "  ws as (\n" +
                        "    select i_item_id,sum(ws_ext_sales_price) total_sales\n" +
                        "    from web_sales, date_dim, customer_address, item\n" +
                        "    where\n" +
                        "        i_item_id in (select i_item_id from item where i_category in ('Music'))\n" +
                        "    and     ws_item_sk              = i_item_sk\n" +
                        "    and     ws_sold_date_sk         = d_date_sk\n" +
                        "    and     d_year                  = 1998\n" +
                        "    and     d_moy                   = 9\n" +
                        "    and     ws_bill_addr_sk         = ca_address_sk\n" +
                        "    and     ca_gmt_offset           = -5\n" +
                        "    group by i_item_id)\n" +
                        " select i_item_id, sum(total_sales) total_sales\n" +
                        " from  (select * from ss\n" +
                        "        union all\n" +
                        "        select * from cs\n" +
                        "        union all\n" +
                        "        select * from ws) tmp1\n" +
                        " group by i_item_id\n" +
                        " order by i_item_id, total_sales\n" +
                        " limit 100"
        );
        tpcdsSQLMap.put("TPCDS61", " select promotions,total,cast(promotions as decimal(15,4))/cast(total as decimal(15,4))*100\n" +
                " from\n" +
                "   (select sum(ss_ext_sales_price) promotions\n" +
                "     from  store_sales, store, promotion, date_dim, customer, customer_address, item\n" +
                "     where ss_sold_date_sk = d_date_sk\n" +
                "     and   ss_store_sk = s_store_sk\n" +
                "     and   ss_promo_sk = p_promo_sk\n" +
                "     and   ss_customer_sk= c_customer_sk\n" +
                "     and   ca_address_sk = c_current_addr_sk\n" +
                "     and   ss_item_sk = i_item_sk\n" +
                "     and   ca_gmt_offset = -5\n" +
                "     and   i_category = 'Jewelry'\n" +
                "     and   (p_channel_dmail = 'Y' or p_channel_email = 'Y' or p_channel_tv = 'Y')\n" +
                "     and   s_gmt_offset = -5\n" +
                "     and   d_year = 1998\n" +
                "     and   d_moy  = 11) promotional_sales cross join\n" +
                "   (select sum(ss_ext_sales_price) total\n" +
                "     from  store_sales, store, date_dim, customer, customer_address, item\n" +
                "     where ss_sold_date_sk = d_date_sk\n" +
                "     and   ss_store_sk = s_store_sk\n" +
                "     and   ss_customer_sk= c_customer_sk\n" +
                "     and   ca_address_sk = c_current_addr_sk\n" +
                "     and   ss_item_sk = i_item_sk\n" +
                "     and   ca_gmt_offset = -5\n" +
                "     and   i_category = 'Jewelry'\n" +
                "     and   s_gmt_offset = -5\n" +
                "     and   d_year = 1998\n" +
                "     and   d_moy  = 11) all_sales\n" +
                " order by promotions, total\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS62", "select\n" +
                "   substr(w_warehouse_name,1,20)\n" +
                "  ,sm_type\n" +
                "  ,web_name\n" +
                "  ,sum(case when (ws_ship_date_sk - ws_sold_date_sk <= 30 ) then 1 else 0 end)  as days\n" +
                "  ,sum(case when (ws_ship_date_sk - ws_sold_date_sk > 30) and\n" +
                "                 (ws_ship_date_sk - ws_sold_date_sk <= 60) then 1 else 0 end )  as days31\n" +
                "  ,sum(case when (ws_ship_date_sk - ws_sold_date_sk > 60) and\n" +
                "                 (ws_ship_date_sk - ws_sold_date_sk <= 90) then 1 else 0 end)  as days61\n" +
                "  ,sum(case when (ws_ship_date_sk - ws_sold_date_sk > 90) and\n" +
                "                 (ws_ship_date_sk - ws_sold_date_sk <= 120) then 1 else 0 end)  as days91\n" +
                "  ,sum(case when (ws_ship_date_sk - ws_sold_date_sk  > 120) then 1 else 0 end)  as days120\n" +
                " from\n" +
                "    web_sales, warehouse, ship_mode, web_site, date_dim\n" +
                " where\n" +
                "     d_month_seq between 1200 and 1200 + 11\n" +
                " and ws_ship_date_sk   = d_date_sk\n" +
                " and ws_warehouse_sk   = w_warehouse_sk\n" +
                " and ws_ship_mode_sk   = sm_ship_mode_sk\n" +
                " and ws_web_site_sk    = web_site_sk\n" +
                " group by\n" +
                "    substr(w_warehouse_name,1,20), sm_type, web_name\n" +
                " order by\n" +
                "    substr(w_warehouse_name,1,20), sm_type, web_name\n" +
                " limit 100\n" +
                "            ");
        tpcdsSQLMap.put("TPCDS63", "select *\n" +
                " from (select i_manager_id\n" +
                "              ,sum(ss_sales_price) sum_sales\n" +
                "              ,avg(sum(ss_sales_price)) over (partition by i_manager_id) avg_monthly_sales\n" +
                "       from item\n" +
                "           ,store_sales\n" +
                "           ,date_dim\n" +
                "           ,store\n" +
                "       where ss_item_sk = i_item_sk\n" +
                "         and ss_sold_date_sk = d_date_sk\n" +
                "         and ss_store_sk = s_store_sk\n" +
                "         and d_month_seq in (1200,1200+1,1200+2,1200+3,1200+4,1200+5,1200+6,1200+7,\n" +
                "                             1200+8,1200+9,1200+10,1200+11)\n" +
                "         and ((    i_category in ('Books','Children','Electronics')\n" +
                "               and i_class in ('personal','portable','reference','self-help')\n" +
                "               and i_brand in ('scholaramalgamalg #14','scholaramalgamalg #7',\n" +
                " \t\t                  'exportiunivamalg #9','scholaramalgamalg #9'))\n" +
                "            or(    i_category in ('Women','Music','Men')\n" +
                "               and i_class in ('accessories','classical','fragrances','pants')\n" +
                "               and i_brand in ('amalgimporto #1','edu packscholar #1','exportiimporto #1',\n" +
                " \t\t                 'importoamalg #1')))\n" +
                " group by i_manager_id, d_moy) tmp1\n" +
                " where case when avg_monthly_sales > 0 then abs (sum_sales - avg_monthly_sales) / avg_monthly_sales else null end > 0.1\n" +
                " order by i_manager_id\n" +
                "         ,avg_monthly_sales\n" +
                "         ,sum_sales\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS64", " with cs_ui as\n" +
                "  (select cs_item_sk\n" +
                "         ,sum(cs_ext_list_price) as sale,sum(cr_refunded_cash+cr_reversed_charge+cr_store_credit) as refund\n" +
                "   from catalog_sales\n" +
                "       ,catalog_returns\n" +
                "   where cs_item_sk = cr_item_sk\n" +
                "     and cs_order_number = cr_order_number\n" +
                "   group by cs_item_sk\n" +
                "   having sum(cs_ext_list_price)>2*sum(cr_refunded_cash+cr_reversed_charge+cr_store_credit)),\n" +
                " cross_sales as\n" +
                "  (select i_product_name product_name, i_item_sk item_sk, s_store_name store_name, s_zip store_zip,\n" +
                "          ad1.ca_street_number b_street_number, ad1.ca_street_name b_streen_name, ad1.ca_city b_city,\n" +
                "          ad1.ca_zip b_zip, ad2.ca_street_number c_street_number, ad2.ca_street_name c_street_name,\n" +
                "          ad2.ca_city c_city, ad2.ca_zip c_zip, d1.d_year as syear, d2.d_year as fsyear, d3.d_year s2year,\n" +
                "          count(*) cnt, sum(ss_wholesale_cost) s1, sum(ss_list_price) s2, sum(ss_coupon_amt) s3\n" +
                "   FROM store_sales, store_returns, cs_ui, date_dim d1, date_dim d2, date_dim d3,\n" +
                "        store, customer, customer_demographics cd1, customer_demographics cd2,\n" +
                "        promotion, household_demographics hd1, household_demographics hd2,\n" +
                "        customer_address ad1, customer_address ad2, income_band ib1, income_band ib2, item\n" +
                "   WHERE  ss_store_sk = s_store_sk AND\n" +
                "          ss_sold_date_sk = d1.d_date_sk AND\n" +
                "          ss_customer_sk = c_customer_sk AND\n" +
                "          ss_cdemo_sk= cd1.cd_demo_sk AND\n" +
                "          ss_hdemo_sk = hd1.hd_demo_sk AND\n" +
                "          ss_addr_sk = ad1.ca_address_sk and\n" +
                "          ss_item_sk = i_item_sk and\n" +
                "          ss_item_sk = sr_item_sk and\n" +
                "          ss_ticket_number = sr_ticket_number and\n" +
                "          ss_item_sk = cs_ui.cs_item_sk and\n" +
                "          c_current_cdemo_sk = cd2.cd_demo_sk AND\n" +
                "          c_current_hdemo_sk = hd2.hd_demo_sk AND\n" +
                "          c_current_addr_sk = ad2.ca_address_sk and\n" +
                "          c_first_sales_date_sk = d2.d_date_sk and\n" +
                "          c_first_shipto_date_sk = d3.d_date_sk and\n" +
                "          ss_promo_sk = p_promo_sk and\n" +
                "          hd1.hd_income_band_sk = ib1.ib_income_band_sk and\n" +
                "          hd2.hd_income_band_sk = ib2.ib_income_band_sk and\n" +
                "          cd1.cd_marital_status <> cd2.cd_marital_status and\n" +
                "          i_color in ('purple','burlywood','indian','spring','floral','medium') and\n" +
                "          i_current_price between 64 and 64 + 10 and\n" +
                "          i_current_price between 64 + 1 and 64 + 15\n" +
                " group by i_product_name, i_item_sk, s_store_name, s_zip, ad1.ca_street_number,\n" +
                "          ad1.ca_street_name, ad1.ca_city, ad1.ca_zip, ad2.ca_street_number,\n" +
                "          ad2.ca_street_name, ad2.ca_city, ad2.ca_zip, d1.d_year, d2.d_year, d3.d_year\n" +
                " )\n" +
                " select cs1.product_name, cs1.store_name, cs1.store_zip, cs1.b_street_number,\n" +
                "        cs1.b_streen_name, cs1.b_city, cs1.b_zip, cs1.c_street_number, cs1.c_street_name,\n" +
                "        cs1.c_city, cs1.c_zip, cs1.syear, cs1.cnt, cs1.s1, cs1.s2, cs1.s3, cs2.s1,\n" +
                "        cs2.s2, cs2.s3, cs2.syear, cs2.cnt\n" +
                " from cross_sales cs1,cross_sales cs2\n" +
                " where cs1.item_sk=cs2.item_sk and\n" +
                "      cs1.syear = 1999 and\n" +
                "      cs2.syear = 1999 + 1 and\n" +
                "      cs2.cnt <= cs1.cnt and\n" +
                "      cs1.store_name = cs2.store_name and\n" +
                "      cs1.store_zip = cs2.store_zip\n" +
                " order by cs1.product_name, cs1.store_name, cs2.cnt");
        tpcdsSQLMap.put("TPCDS65", " select\n" +
                "\t  s_store_name, i_item_desc, sc.revenue, i_current_price, i_wholesale_cost, i_brand\n" +
                " from store, item,\n" +
                "     (select ss_store_sk, avg(revenue) as ave\n" +
                " \tfrom\n" +
                " \t    (select  ss_store_sk, ss_item_sk,\n" +
                " \t\t     sum(ss_sales_price) as revenue\n" +
                " \t\tfrom store_sales, date_dim\n" +
                " \t\twhere ss_sold_date_sk = d_date_sk and d_month_seq between 1176 and 1176+11\n" +
                " \t\tgroup by ss_store_sk, ss_item_sk) sa\n" +
                " \tgroup by ss_store_sk) sb,\n" +
                "     (select  ss_store_sk, ss_item_sk, sum(ss_sales_price) as revenue\n" +
                " \tfrom store_sales, date_dim\n" +
                " \twhere ss_sold_date_sk = d_date_sk and d_month_seq between 1176 and 1176+11\n" +
                " \tgroup by ss_store_sk, ss_item_sk) sc\n" +
                " where sb.ss_store_sk = sc.ss_store_sk and\n" +
                "       sc.revenue <= 0.1 * sb.ave and\n" +
                "       s_store_sk = sc.ss_store_sk and\n" +
                "       i_item_sk = sc.ss_item_sk\n" +
                " order by s_store_name, i_item_desc\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS66", " select w_warehouse_name, w_warehouse_sq_ft, w_city, w_county, w_state, w_country,\n" +
                "    ship_carriers, year\n" +
                " \t  ,sum(jan_sales) as jan_sales\n" +
                " \t  ,sum(feb_sales) as feb_sales\n" +
                " \t  ,sum(mar_sales) as mar_sales\n" +
                " \t  ,sum(apr_sales) as apr_sales\n" +
                " \t  ,sum(may_sales) as may_sales\n" +
                " \t  ,sum(jun_sales) as jun_sales\n" +
                " \t  ,sum(jul_sales) as jul_sales\n" +
                " \t  ,sum(aug_sales) as aug_sales\n" +
                " \t  ,sum(sep_sales) as sep_sales\n" +
                " \t  ,sum(oct_sales) as oct_sales\n" +
                " \t  ,sum(nov_sales) as nov_sales\n" +
                " \t  ,sum(dec_sales) as dec_sales\n" +
                " \t  ,sum(jan_sales/w_warehouse_sq_ft) as jan_sales_per_sq_foot\n" +
                " \t  ,sum(feb_sales/w_warehouse_sq_ft) as feb_sales_per_sq_foot\n" +
                " \t  ,sum(mar_sales/w_warehouse_sq_ft) as mar_sales_per_sq_foot\n" +
                " \t  ,sum(apr_sales/w_warehouse_sq_ft) as apr_sales_per_sq_foot\n" +
                " \t  ,sum(may_sales/w_warehouse_sq_ft) as may_sales_per_sq_foot\n" +
                " \t  ,sum(jun_sales/w_warehouse_sq_ft) as jun_sales_per_sq_foot\n" +
                " \t  ,sum(jul_sales/w_warehouse_sq_ft) as jul_sales_per_sq_foot\n" +
                " \t  ,sum(aug_sales/w_warehouse_sq_ft) as aug_sales_per_sq_foot\n" +
                " \t  ,sum(sep_sales/w_warehouse_sq_ft) as sep_sales_per_sq_foot\n" +
                " \t  ,sum(oct_sales/w_warehouse_sq_ft) as oct_sales_per_sq_foot\n" +
                " \t  ,sum(nov_sales/w_warehouse_sq_ft) as nov_sales_per_sq_foot\n" +
                " \t  ,sum(dec_sales/w_warehouse_sq_ft) as dec_sales_per_sq_foot\n" +
                " \t  ,sum(jan_net) as jan_net\n" +
                " \t  ,sum(feb_net) as feb_net\n" +
                " \t  ,sum(mar_net) as mar_net\n" +
                " \t  ,sum(apr_net) as apr_net\n" +
                " \t  ,sum(may_net) as may_net\n" +
                " \t  ,sum(jun_net) as jun_net\n" +
                " \t  ,sum(jul_net) as jul_net\n" +
                " \t  ,sum(aug_net) as aug_net\n" +
                " \t  ,sum(sep_net) as sep_net\n" +
                " \t  ,sum(oct_net) as oct_net\n" +
                " \t  ,sum(nov_net) as nov_net\n" +
                " \t  ,sum(dec_net) as dec_net\n" +
                " from (\n" +
                "    (select\n" +
                " \t    w_warehouse_name, w_warehouse_sq_ft, w_city, w_county, w_state, w_country\n" +
                " \t        ,concat('DHL', ',', 'BARIAN') as ship_carriers\n" +
                "      ,d_year as year\n" +
                " \t    ,sum(case when d_moy = 1 then ws_ext_sales_price * ws_quantity else 0 end) as jan_sales\n" +
                " \t    ,sum(case when d_moy = 2 then ws_ext_sales_price * ws_quantity else 0 end) as feb_sales\n" +
                " \t    ,sum(case when d_moy = 3 then ws_ext_sales_price * ws_quantity else 0 end) as mar_sales\n" +
                " \t    ,sum(case when d_moy = 4 then ws_ext_sales_price * ws_quantity else 0 end) as apr_sales\n" +
                " \t    ,sum(case when d_moy = 5 then ws_ext_sales_price * ws_quantity else 0 end) as may_sales\n" +
                " \t    ,sum(case when d_moy = 6 then ws_ext_sales_price * ws_quantity else 0 end) as jun_sales\n" +
                " \t    ,sum(case when d_moy = 7 then ws_ext_sales_price * ws_quantity else 0 end) as jul_sales\n" +
                " \t    ,sum(case when d_moy = 8 then ws_ext_sales_price * ws_quantity else 0 end) as aug_sales\n" +
                " \t    ,sum(case when d_moy = 9 then ws_ext_sales_price * ws_quantity else 0 end) as sep_sales\n" +
                " \t    ,sum(case when d_moy = 10 then ws_ext_sales_price * ws_quantity else 0 end) as oct_sales\n" +
                " \t    ,sum(case when d_moy = 11 then ws_ext_sales_price * ws_quantity else 0 end) as nov_sales\n" +
                " \t    ,sum(case when d_moy = 12 then ws_ext_sales_price * ws_quantity else 0 end) as dec_sales\n" +
                " \t    ,sum(case when d_moy = 1 then ws_net_paid * ws_quantity else 0 end) as jan_net\n" +
                " \t    ,sum(case when d_moy = 2 then ws_net_paid * ws_quantity else 0 end) as feb_net\n" +
                " \t    ,sum(case when d_moy = 3 then ws_net_paid * ws_quantity else 0 end) as mar_net\n" +
                " \t    ,sum(case when d_moy = 4 then ws_net_paid * ws_quantity else 0 end) as apr_net\n" +
                " \t    ,sum(case when d_moy = 5 then ws_net_paid * ws_quantity else 0 end) as may_net\n" +
                " \t    ,sum(case when d_moy = 6 then ws_net_paid * ws_quantity else 0 end) as jun_net\n" +
                " \t    ,sum(case when d_moy = 7 then ws_net_paid * ws_quantity else 0 end) as jul_net\n" +
                " \t    ,sum(case when d_moy = 8 then ws_net_paid * ws_quantity else 0 end) as aug_net\n" +
                " \t    ,sum(case when d_moy = 9 then ws_net_paid * ws_quantity else 0 end) as sep_net\n" +
                " \t    ,sum(case when d_moy = 10 then ws_net_paid * ws_quantity else 0 end) as oct_net\n" +
                " \t    ,sum(case when d_moy = 11 then ws_net_paid * ws_quantity else 0 end) as nov_net\n" +
                " \t    ,sum(case when d_moy = 12 then ws_net_paid * ws_quantity else 0 end) as dec_net\n" +
                "    from\n" +
                "      web_sales, warehouse, date_dim, time_dim, ship_mode\n" +
                "    where\n" +
                "      ws_warehouse_sk =  w_warehouse_sk\n" +
                "      and ws_sold_date_sk = d_date_sk\n" +
                "      and ws_sold_time_sk = t_time_sk\n" +
                " \t    and ws_ship_mode_sk = sm_ship_mode_sk\n" +
                "      and d_year = 2001\n" +
                " \t    and t_time between 30838 and 30838+28800\n" +
                " \t    and sm_carrier in ('DHL','BARIAN')\n" +
                "   group by\n" +
                "      w_warehouse_name, w_warehouse_sq_ft, w_city, w_county, w_state, w_country, d_year)\n" +
                " union all\n" +
                "    (select w_warehouse_name, w_warehouse_sq_ft, w_city, w_county, w_state, w_country\n" +
                " \t        ,concat('DHL', ',', 'BARIAN') as ship_carriers\n" +
                "      ,d_year as year\n" +
                " \t    ,sum(case when d_moy = 1 then cs_sales_price * cs_quantity else 0 end) as jan_sales\n" +
                " \t    ,sum(case when d_moy = 2 then cs_sales_price * cs_quantity else 0 end) as feb_sales\n" +
                " \t    ,sum(case when d_moy = 3 then cs_sales_price * cs_quantity else 0 end) as mar_sales\n" +
                " \t    ,sum(case when d_moy = 4 then cs_sales_price * cs_quantity else 0 end) as apr_sales\n" +
                " \t    ,sum(case when d_moy = 5 then cs_sales_price * cs_quantity else 0 end) as may_sales\n" +
                " \t    ,sum(case when d_moy = 6 then cs_sales_price * cs_quantity else 0 end) as jun_sales\n" +
                " \t    ,sum(case when d_moy = 7 then cs_sales_price * cs_quantity else 0 end) as jul_sales\n" +
                " \t    ,sum(case when d_moy = 8 then cs_sales_price * cs_quantity else 0 end) as aug_sales\n" +
                " \t    ,sum(case when d_moy = 9 then cs_sales_price * cs_quantity else 0 end) as sep_sales\n" +
                " \t    ,sum(case when d_moy = 10 then cs_sales_price * cs_quantity else 0 end) as oct_sales\n" +
                " \t    ,sum(case when d_moy = 11 then cs_sales_price * cs_quantity else 0 end) as nov_sales\n" +
                " \t    ,sum(case when d_moy = 12 then cs_sales_price * cs_quantity else 0 end) as dec_sales\n" +
                " \t    ,sum(case when d_moy = 1 then cs_net_paid_inc_tax * cs_quantity else 0 end) as jan_net\n" +
                " \t    ,sum(case when d_moy = 2 then cs_net_paid_inc_tax * cs_quantity else 0 end) as feb_net\n" +
                " \t    ,sum(case when d_moy = 3 then cs_net_paid_inc_tax * cs_quantity else 0 end) as mar_net\n" +
                " \t    ,sum(case when d_moy = 4 then cs_net_paid_inc_tax * cs_quantity else 0 end) as apr_net\n" +
                " \t    ,sum(case when d_moy = 5 then cs_net_paid_inc_tax * cs_quantity else 0 end) as may_net\n" +
                " \t    ,sum(case when d_moy = 6 then cs_net_paid_inc_tax * cs_quantity else 0 end) as jun_net\n" +
                " \t    ,sum(case when d_moy = 7 then cs_net_paid_inc_tax * cs_quantity else 0 end) as jul_net\n" +
                " \t    ,sum(case when d_moy = 8 then cs_net_paid_inc_tax * cs_quantity else 0 end) as aug_net\n" +
                " \t    ,sum(case when d_moy = 9 then cs_net_paid_inc_tax * cs_quantity else 0 end) as sep_net\n" +
                " \t    ,sum(case when d_moy = 10 then cs_net_paid_inc_tax * cs_quantity else 0 end) as oct_net\n" +
                " \t    ,sum(case when d_moy = 11 then cs_net_paid_inc_tax * cs_quantity else 0 end) as nov_net\n" +
                " \t    ,sum(case when d_moy = 12 then cs_net_paid_inc_tax * cs_quantity else 0 end) as dec_net\n" +
                "     from\n" +
                "        catalog_sales, warehouse, date_dim, time_dim, ship_mode\n" +
                "     where\n" +
                "        cs_warehouse_sk =  w_warehouse_sk\n" +
                "        and cs_sold_date_sk = d_date_sk\n" +
                "        and cs_sold_time_sk = t_time_sk\n" +
                " \t      and cs_ship_mode_sk = sm_ship_mode_sk\n" +
                "        and d_year = 2001\n" +
                " \t      and t_time between 30838 AND 30838+28800\n" +
                " \t      and sm_carrier in ('DHL','BARIAN')\n" +
                "     group by\n" +
                "        w_warehouse_name, w_warehouse_sq_ft, w_city, w_county, w_state, w_country, d_year\n" +
                "     )\n" +
                " ) x\n" +
                " group by\n" +
                "    w_warehouse_name, w_warehouse_sq_ft, w_city, w_county, w_state, w_country,\n" +
                "    ship_carriers, year\n" +
                " order by w_warehouse_name\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS67", " select * from\n" +
                "     (select i_category, i_class, i_brand, i_product_name, d_year, d_qoy, d_moy, s_store_id,\n" +
                "             sumsales, rank() over (partition by i_category order by sumsales desc) rk\n" +
                "      from\n" +
                "         (select i_category, i_class, i_brand, i_product_name, d_year, d_qoy, d_moy,\n" +
                "                 s_store_id, sum(coalesce(ss_sales_price*ss_quantity,0)) sumsales\n" +
                "          from store_sales, date_dim, store, item\n" +
                "        where  ss_sold_date_sk=d_date_sk\n" +
                "           and ss_item_sk=i_item_sk\n" +
                "           and ss_store_sk = s_store_sk\n" +
                "           and d_month_seq between 1200 and 1200+11\n" +
                "        group by rollup(i_category, i_class, i_brand, i_product_name, d_year, d_qoy,\n" +
                "                        d_moy,s_store_id))dw1) dw2\n" +
                " where rk <= 100\n" +
                " order by\n" +
                "   i_category, i_class, i_brand, i_product_name, d_year,\n" +
                "   d_qoy, d_moy, s_store_id, sumsales, rk\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS68", " select\n" +
                "    c_last_name, c_first_name, ca_city, bought_city, ss_ticket_number, extended_price,\n" +
                "    extended_tax, list_price\n" +
                " from (select\n" +
                "        ss_ticket_number, ss_customer_sk, ca_city bought_city,\n" +
                "        sum(ss_ext_sales_price) extended_price,\n" +
                "        sum(ss_ext_list_price) list_price,\n" +
                "        sum(ss_ext_tax) extended_tax\n" +
                "     from store_sales, date_dim, store, household_demographics, customer_address\n" +
                "     where store_sales.ss_sold_date_sk = date_dim.d_date_sk\n" +
                "        and store_sales.ss_store_sk = store.s_store_sk\n" +
                "        and store_sales.ss_hdemo_sk = household_demographics.hd_demo_sk\n" +
                "        and store_sales.ss_addr_sk = customer_address.ca_address_sk\n" +
                "        and date_dim.d_dom between 1 and 2\n" +
                "        and (household_demographics.hd_dep_count = 4 or\n" +
                "             household_demographics.hd_vehicle_count = 3)\n" +
                "        and date_dim.d_year in (1999,1999+1,1999+2)\n" +
                "        and store.s_city in ('Midway','Fairview')\n" +
                "     group by ss_ticket_number, ss_customer_sk, ss_addr_sk,ca_city) dn,\n" +
                "    customer,\n" +
                "    customer_address current_addr\n" +
                " where ss_customer_sk = c_customer_sk\n" +
                "   and customer.c_current_addr_sk = current_addr.ca_address_sk\n" +
                "   and current_addr.ca_city <> bought_city\n" +
                " order by c_last_name, ss_ticket_number\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS69", " select\n" +
                "    cd_gender, cd_marital_status, cd_education_status, count(*) cnt1,\n" +
                "    cd_purchase_estimate, count(*) cnt2, cd_credit_rating, count(*) cnt3\n" +
                " from\n" +
                "    customer c,customer_address ca,customer_demographics\n" +
                " where\n" +
                "    c.c_current_addr_sk = ca.ca_address_sk and\n" +
                "    ca_state in ('KY', 'GA', 'NM') and\n" +
                "    cd_demo_sk = c.c_current_cdemo_sk and\n" +
                "    exists (select * from store_sales, date_dim\n" +
                "            where c.c_customer_sk = ss_customer_sk and\n" +
                "                ss_sold_date_sk = d_date_sk and\n" +
                "                d_year = 2001 and\n" +
                "                d_moy between 4 and 4+2) and\n" +
                "   (not exists (select * from web_sales, date_dim\n" +
                "                where c.c_customer_sk = ws_bill_customer_sk and\n" +
                "                    ws_sold_date_sk = d_date_sk and\n" +
                "                    d_year = 2001 and\n" +
                "                    d_moy between 4 and 4+2) and\n" +
                "    not exists (select * from catalog_sales, date_dim\n" +
                "                where c.c_customer_sk = cs_ship_customer_sk and\n" +
                "                    cs_sold_date_sk = d_date_sk and\n" +
                "                    d_year = 2001 and\n" +
                "                    d_moy between 4 and 4+2))\n" +
                " group by cd_gender, cd_marital_status, cd_education_status,\n" +
                "          cd_purchase_estimate, cd_credit_rating\n" +
                " order by cd_gender, cd_marital_status, cd_education_status,\n" +
                "          cd_purchase_estimate, cd_credit_rating\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS70", " select\n" +
                "    sum(ss_net_profit) as total_sum, s_state, s_county\n" +
                "   ,grouping(s_state)+grouping(s_county) as lochierarchy\n" +
                "   ,rank() over (\n" +
                " \t    partition by grouping(s_state)+grouping(s_county),\n" +
                " \t    case when grouping(s_county) = 0 then s_state end\n" +
                " \t    order by sum(ss_net_profit) desc) as rank_within_parent\n" +
                " from\n" +
                "    store_sales, date_dim d1, store\n" +
                " where\n" +
                "    d1.d_month_seq between 1200 and 1200+11\n" +
                " and d1.d_date_sk = ss_sold_date_sk\n" +
                " and s_store_sk  = ss_store_sk\n" +
                " and s_state in\n" +
                "    (select s_state from\n" +
                "        (select s_state as s_state,\n" +
                " \t\t\t      rank() over ( partition by s_state order by sum(ss_net_profit) desc) as ranking\n" +
                "         from store_sales, store, date_dim\n" +
                "         where  d_month_seq between 1200 and 1200+11\n" +
                " \t\t\t   and d_date_sk = ss_sold_date_sk\n" +
                " \t\t\t   and s_store_sk  = ss_store_sk\n" +
                "         group by s_state) tmp1\n" +
                "     where ranking <= 5)\n" +
                " group by rollup(s_state,s_county)\n" +
                " order by\n" +
                "   lochierarchy desc\n" +
                "  ,case when lochierarchy = 0 then s_state end\n" +
                "  ,rank_within_parent\n" +
                " limit 100");

        tpcdsSQLMap.put("TPCDS71", " select i_brand_id brand_id, i_brand brand,t_hour,t_minute,\n" +
                " \t  sum(ext_price) ext_price\n" +
                " from item,\n" +
                "    (select\n" +
                "        ws_ext_sales_price as ext_price,\n" +
                "        ws_sold_date_sk as sold_date_sk,\n" +
                "        ws_item_sk as sold_item_sk,\n" +
                "        ws_sold_time_sk as time_sk\n" +
                "     from web_sales, date_dim\n" +
                "     where d_date_sk = ws_sold_date_sk\n" +
                "        and d_moy=11\n" +
                "        and d_year=1999\n" +
                "     union all\n" +
                "     select\n" +
                "        cs_ext_sales_price as ext_price,\n" +
                "        cs_sold_date_sk as sold_date_sk,\n" +
                "        cs_item_sk as sold_item_sk,\n" +
                "        cs_sold_time_sk as time_sk\n" +
                "      from catalog_sales, date_dim\n" +
                "      where d_date_sk = cs_sold_date_sk\n" +
                "          and d_moy=11\n" +
                "          and d_year=1999\n" +
                "     union all\n" +
                "     select\n" +
                "        ss_ext_sales_price as ext_price,\n" +
                "        ss_sold_date_sk as sold_date_sk,\n" +
                "        ss_item_sk as sold_item_sk,\n" +
                "        ss_sold_time_sk as time_sk\n" +
                "     from store_sales,date_dim\n" +
                "     where d_date_sk = ss_sold_date_sk\n" +
                "        and d_moy=11\n" +
                "        and d_year=1999\n" +
                "     ) tmp, time_dim\n" +
                " where\n" +
                "   sold_item_sk = i_item_sk\n" +
                "   and i_manager_id=1\n" +
                "   and time_sk = t_time_sk\n" +
                "   and (t_meal_time = 'breakfast' or t_meal_time = 'dinner')\n" +
                " group by i_brand, i_brand_id,t_hour,t_minute\n" +
                " order by ext_price desc, i_brand_id");
        tpcdsSQLMap.put("TPCDS72", " select i_item_desc\n" +
                "       ,w_warehouse_name\n" +
                "       ,d1.d_week_seq\n" +
                "       ,sum(case when p_promo_sk is null then 1 else 0 end) no_promo\n" +
                "       ,sum(case when p_promo_sk is not null then 1 else 0 end) promo\n" +
                "       ,count(*) total_cnt\n" +
                " from catalog_sales\n" +
                " join inventory on (cs_item_sk = inv_item_sk)\n" +
                " join warehouse on (w_warehouse_sk=inv_warehouse_sk)\n" +
                " join item on (i_item_sk = cs_item_sk)\n" +
                " join customer_demographics on (cs_bill_cdemo_sk = cd_demo_sk)\n" +
                " join household_demographics on (cs_bill_hdemo_sk = hd_demo_sk)\n" +
                " join date_dim d1 on (cs_sold_date_sk = d1.d_date_sk)\n" +
                " join date_dim d2 on (inv_date_sk = d2.d_date_sk)\n" +
                " join date_dim d3 on (cs_ship_date_sk = d3.d_date_sk)\n" +
                " left outer join promotion on (cs_promo_sk=p_promo_sk)\n" +
                " left outer join catalog_returns on (cr_item_sk = cs_item_sk and cr_order_number = cs_order_number)\n" +
                " where d1.d_week_seq = d2.d_week_seq\n" +
                "   and inv_quantity_on_hand < cs_quantity\n" +
                "   and d3.d_date > (cast(d1.d_date AS DATE) + interval '5' day)\n" +
                "   and hd_buy_potential = '>10000'\n" +
                "   and d1.d_year = 1999\n" +
                "   and cd_marital_status = 'D'\n" +
                " group by i_item_desc,w_warehouse_name,d1.d_week_seq\n" +
                " order by total_cnt desc, i_item_desc, w_warehouse_name, d1.d_week_seq\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS73", " select\n" +
                "    c_last_name, c_first_name, c_salutation, c_preferred_cust_flag,\n" +
                "    ss_ticket_number, cnt from\n" +
                "   (select ss_ticket_number, ss_customer_sk, count(*) cnt\n" +
                "    from store_sales,date_dim,store,household_demographics\n" +
                "    where store_sales.ss_sold_date_sk = date_dim.d_date_sk\n" +
                "    and store_sales.ss_store_sk = store.s_store_sk\n" +
                "    and store_sales.ss_hdemo_sk = household_demographics.hd_demo_sk\n" +
                "    and date_dim.d_dom between 1 and 2\n" +
                "    and (household_demographics.hd_buy_potential = '>10000' or\n" +
                "         household_demographics.hd_buy_potential = 'unknown')\n" +
                "    and household_demographics.hd_vehicle_count > 0\n" +
                "    and case when household_demographics.hd_vehicle_count > 0 then\n" +
                "             household_demographics.hd_dep_count/ household_demographics.hd_vehicle_count else null end > 1\n" +
                "    and date_dim.d_year in (1999,1999+1,1999+2)\n" +
                "    and store.s_county in ('Williamson County','Franklin Parish','Bronx County','Orange County')\n" +
                "    group by ss_ticket_number,ss_customer_sk) dj,customer\n" +
                "    where ss_customer_sk = c_customer_sk\n" +
                "      and cnt between 1 and 5\n" +
                "    order by cnt desc, c_last_name asc");
        tpcdsSQLMap.put("TPCDS74", " with year_total as (\n" +
                " select\n" +
                "    c_customer_id customer_id, c_first_name customer_first_name,\n" +
                "    c_last_name customer_last_name, d_year as year,\n" +
                "    sum(ss_net_paid) year_total, 's' sale_type\n" +
                " from\n" +
                "    customer, store_sales, date_dim\n" +
                " where c_customer_sk = ss_customer_sk\n" +
                "    and ss_sold_date_sk = d_date_sk\n" +
                "    and d_year in (2001,2001+1)\n" +
                " group by\n" +
                "    c_customer_id, c_first_name, c_last_name, d_year\n" +
                " union all\n" +
                " select\n" +
                "    c_customer_id customer_id, c_first_name customer_first_name,\n" +
                "    c_last_name customer_last_name, d_year as year,\n" +
                "    sum(ws_net_paid) year_total, 'w' sale_type\n" +
                " from\n" +
                "    customer, web_sales, date_dim\n" +
                " where c_customer_sk = ws_bill_customer_sk\n" +
                "    and ws_sold_date_sk = d_date_sk\n" +
                "    and d_year in (2001,2001+1)\n" +
                " group by\n" +
                "    c_customer_id, c_first_name, c_last_name, d_year)\n" +
                " select\n" +
                "    t_s_secyear.customer_id, t_s_secyear.customer_first_name, t_s_secyear.customer_last_name\n" +
                " from\n" +
                "    year_total t_s_firstyear, year_total t_s_secyear,\n" +
                "    year_total t_w_firstyear, year_total t_w_secyear\n" +
                " where t_s_secyear.customer_id = t_s_firstyear.customer_id\n" +
                "    and t_s_firstyear.customer_id = t_w_secyear.customer_id\n" +
                "    and t_s_firstyear.customer_id = t_w_firstyear.customer_id\n" +
                "    and t_s_firstyear.sale_type = 's'\n" +
                "    and t_w_firstyear.sale_type = 'w'\n" +
                "    and t_s_secyear.sale_type = 's'\n" +
                "    and t_w_secyear.sale_type = 'w'\n" +
                "    and t_s_firstyear.year = 2001\n" +
                "    and t_s_secyear.year = 2001+1\n" +
                "    and t_w_firstyear.year = 2001\n" +
                "    and t_w_secyear.year = 2001+1\n" +
                "    and t_s_firstyear.year_total > 0\n" +
                "    and t_w_firstyear.year_total > 0\n" +
                "    and case when t_w_firstyear.year_total > 0 then t_w_secyear.year_total / t_w_firstyear.year_total else null end\n" +
                "      > case when t_s_firstyear.year_total > 0 then t_s_secyear.year_total / t_s_firstyear.year_total else null end\n" +
                " order by 1, 1, 1\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS75", " WITH all_sales AS (\n" +
                "    SELECT\n" +
                "        d_year, i_brand_id, i_class_id, i_category_id, i_manufact_id,\n" +
                "        SUM(sales_cnt) AS sales_cnt, SUM(sales_amt) AS sales_amt\n" +
                "    FROM (\n" +
                "        SELECT\n" +
                "            d_year, i_brand_id, i_class_id, i_category_id, i_manufact_id,\n" +
                "            cs_quantity - COALESCE(cr_return_quantity,0) AS sales_cnt,\n" +
                "            cs_ext_sales_price - COALESCE(cr_return_amount,0.0) AS sales_amt\n" +
                "        FROM catalog_sales\n" +
                "        JOIN item ON i_item_sk=cs_item_sk\n" +
                "        JOIN date_dim ON d_date_sk=cs_sold_date_sk\n" +
                "        LEFT JOIN catalog_returns ON (cs_order_number=cr_order_number\n" +
                "                                      AND cs_item_sk=cr_item_sk)\n" +
                "        WHERE i_category='Books'\n" +
                "        UNION\n" +
                "        SELECT\n" +
                "            d_year, i_brand_id, i_class_id, i_category_id, i_manufact_id,\n" +
                "             ss_quantity - COALESCE(sr_return_quantity,0) AS sales_cnt,\n" +
                "             ss_ext_sales_price - COALESCE(sr_return_amt,0.0) AS sales_amt\n" +
                "        FROM store_sales\n" +
                "        JOIN item ON i_item_sk=ss_item_sk\n" +
                "        JOIN date_dim ON d_date_sk=ss_sold_date_sk\n" +
                "        LEFT JOIN store_returns ON (ss_ticket_number=sr_ticket_number\n" +
                "                                    AND ss_item_sk=sr_item_sk)\n" +
                "        WHERE i_category='Books'\n" +
                "        UNION\n" +
                "        SELECT\n" +
                "            d_year, i_brand_id, i_class_id, i_category_id, i_manufact_id,\n" +
                "            ws_quantity - COALESCE(wr_return_quantity,0) AS sales_cnt,\n" +
                "            ws_ext_sales_price - COALESCE(wr_return_amt,0.0) AS sales_amt\n" +
                "        FROM web_sales\n" +
                "        JOIN item ON i_item_sk=ws_item_sk\n" +
                "        JOIN date_dim ON d_date_sk=ws_sold_date_sk\n" +
                "        LEFT JOIN web_returns ON (ws_order_number=wr_order_number\n" +
                "                                  AND ws_item_sk=wr_item_sk)\n" +
                "        WHERE i_category='Books') sales_detail\n" +
                "    GROUP BY d_year, i_brand_id, i_class_id, i_category_id, i_manufact_id)\n" +
                " SELECT\n" +
                "    prev_yr.d_year AS prev_year, curr_yr.d_year AS year, curr_yr.i_brand_id,\n" +
                "    curr_yr.i_class_id, curr_yr.i_category_id, curr_yr.i_manufact_id,\n" +
                "    prev_yr.sales_cnt AS prev_yr_cnt, curr_yr.sales_cnt AS curr_yr_cnt,\n" +
                "    curr_yr.sales_cnt-prev_yr.sales_cnt AS sales_cnt_diff,\n" +
                "    curr_yr.sales_amt-prev_yr.sales_amt AS sales_amt_diff\n" +
                " FROM all_sales curr_yr, all_sales prev_yr\n" +
                " WHERE curr_yr.i_brand_id=prev_yr.i_brand_id\n" +
                "   AND curr_yr.i_class_id=prev_yr.i_class_id\n" +
                "   AND curr_yr.i_category_id=prev_yr.i_category_id\n" +
                "   AND curr_yr.i_manufact_id=prev_yr.i_manufact_id\n" +
                "   AND curr_yr.d_year=2002\n" +
                "   AND prev_yr.d_year=2002-1\n" +
                "   AND CAST(curr_yr.sales_cnt AS DECIMAL(17,2))/CAST(prev_yr.sales_cnt AS DECIMAL(17,2))<0.9\n" +
                " ORDER BY sales_cnt_diff\n" +
                " LIMIT 100");
        tpcdsSQLMap.put("TPCDS76", " SELECT\n" +
                "    channel, col_name, d_year, d_qoy, i_category, COUNT(*) sales_cnt,\n" +
                "    SUM(ext_sales_price) sales_amt\n" +
                " FROM(\n" +
                "    SELECT\n" +
                "        'store' as channel, ss_store_sk col_name, d_year, d_qoy, i_category,\n" +
                "        ss_ext_sales_price ext_sales_price\n" +
                "    FROM store_sales, item, date_dim\n" +
                "    WHERE ss_store_sk IS NULL\n" +
                "      AND ss_sold_date_sk=d_date_sk\n" +
                "      AND ss_item_sk=i_item_sk\n" +
                "    UNION ALL\n" +
                "    SELECT\n" +
                "        'web' as channel, ws_ship_customer_sk col_name, d_year, d_qoy, i_category,\n" +
                "        ws_ext_sales_price ext_sales_price\n" +
                "    FROM web_sales, item, date_dim\n" +
                "    WHERE ws_ship_customer_sk IS NULL\n" +
                "      AND ws_sold_date_sk=d_date_sk\n" +
                "      AND ws_item_sk=i_item_sk\n" +
                "    UNION ALL\n" +
                "    SELECT\n" +
                "        'catalog' as channel, cs_ship_addr_sk col_name, d_year, d_qoy, i_category,\n" +
                "        cs_ext_sales_price ext_sales_price\n" +
                "    FROM catalog_sales, item, date_dim\n" +
                "    WHERE cs_ship_addr_sk IS NULL\n" +
                "      AND cs_sold_date_sk=d_date_sk\n" +
                "      AND cs_item_sk=i_item_sk) foo\n" +
                " GROUP BY channel, col_name, d_year, d_qoy, i_category\n" +
                " ORDER BY channel, col_name, d_year, d_qoy, i_category\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS77", "  with ss as\n" +
                " (select s_store_sk, sum(ss_ext_sales_price) as sales, sum(ss_net_profit) as profit\n" +
                "  from store_sales, date_dim, store\n" +
                "  where ss_sold_date_sk = d_date_sk\n" +
                "    and d_date between cast('2000-08-23' as date) and\n" +
                "                       (cast('2000-08-23' as date) + interval '30' day)\n" +
                "    and ss_store_sk = s_store_sk\n" +
                "  group by s_store_sk),\n" +
                " sr as\n" +
                " (select s_store_sk, sum(sr_return_amt) as returns, sum(sr_net_loss) as profit_loss\n" +
                " from store_returns, date_dim, store\n" +
                " where sr_returned_date_sk = d_date_sk\n" +
                "    and d_date between cast('2000-08-23' as date) and\n" +
                "                       (cast('2000-08-23' as date) + interval '30' day)\n" +
                "    and sr_store_sk = s_store_sk\n" +
                " group by s_store_sk),\n" +
                " cs as\n" +
                " (select cs_call_center_sk, sum(cs_ext_sales_price) as sales, sum(cs_net_profit) as profit\n" +
                " from catalog_sales, date_dim\n" +
                " where cs_sold_date_sk = d_date_sk\n" +
                "    and d_date between cast('2000-08-23' as date) and\n" +
                "                       (cast('2000-08-23' as date) + interval '30' day)\n" +
                " group by cs_call_center_sk),\n" +
                " cr as\n" +
                " (select cr_call_center_sk, sum(cr_return_amount) as returns, sum(cr_net_loss) as profit_loss\n" +
                " from catalog_returns, date_dim\n" +
                " where cr_returned_date_sk = d_date_sk\n" +
                "    and d_date between cast('2000-08-23' as date) and\n" +
                "                       (cast('2000-08-23' as date) + interval '30' day)\n" +
                "\tgroup by cr_call_center_sk),\n" +
                " ws as\n" +
                " (select wp_web_page_sk, sum(ws_ext_sales_price) as sales, sum(ws_net_profit) as profit\n" +
                " from web_sales, date_dim, web_page\n" +
                " where ws_sold_date_sk = d_date_sk\n" +
                "    and d_date between cast('2000-08-23' as date) and\n" +
                "                       (cast('2000-08-23' as date) + interval '30' day)\n" +
                "    and ws_web_page_sk = wp_web_page_sk\n" +
                " group by wp_web_page_sk),\n" +
                " wr as\n" +
                " (select wp_web_page_sk, sum(wr_return_amt) as returns, sum(wr_net_loss) as profit_loss\n" +
                " from web_returns, date_dim, web_page\n" +
                " where wr_returned_date_sk = d_date_sk\n" +
                "       and d_date between cast('2000-08-23' as date) and\n" +
                "                          (cast('2000-08-23' as date) + interval '30' day)\n" +
                "       and wr_web_page_sk = wp_web_page_sk\n" +
                " group by wp_web_page_sk)\n" +
                " select channel, id, sum(sales) as sales, sum(\"returns\") as returns1, sum(profit) as profit\n" +
                " from\n" +
                " (select\n" +
                "    'store channel' as channel, ss.s_store_sk as id, sales,\n" +
                "    coalesce(returns, 0) as returns, (profit - coalesce(profit_loss,0)) as profit\n" +
                " from ss left join sr\n" +
                "      on  ss.s_store_sk = sr.s_store_sk\n" +
                " union all\n" +
                " select\n" +
                "    'catalog channel' as channel, cs_call_center_sk as id, sales,\n" +
                "    returns, (profit - profit_loss) as profit\n" +
                " from cs cross join cr\n" +
                " union all\n" +
                " select\n" +
                "    'web channel' as channel, ws.wp_web_page_sk as id, sales,\n" +
                "    coalesce(\"returns\", 0) returns1, (profit - coalesce(profit_loss,0)) as profit\n" +
                " from   ws left join wr\n" +
                "        on  ws.wp_web_page_sk = wr.wp_web_page_sk\n" +
                " ) x\n" +
                " group by rollup(channel, id)\n" +
                " order by channel, id\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS78", " with ws as\n" +
                "   (select d_year AS ws_sold_year, ws_item_sk,\n" +
                "     ws_bill_customer_sk ws_customer_sk,\n" +
                "     sum(ws_quantity) ws_qty,\n" +
                "     sum(ws_wholesale_cost) ws_wc,\n" +
                "     sum(ws_sales_price) ws_sp\n" +
                "    from web_sales\n" +
                "    left join web_returns on wr_order_number=ws_order_number and ws_item_sk=wr_item_sk\n" +
                "    join date_dim on ws_sold_date_sk = d_date_sk\n" +
                "    where wr_order_number is null\n" +
                "    group by d_year, ws_item_sk, ws_bill_customer_sk\n" +
                "    ),\n" +
                " cs as\n" +
                "   (select d_year AS cs_sold_year, cs_item_sk,\n" +
                "     cs_bill_customer_sk cs_customer_sk,\n" +
                "     sum(cs_quantity) cs_qty,\n" +
                "     sum(cs_wholesale_cost) cs_wc,\n" +
                "     sum(cs_sales_price) cs_sp\n" +
                "    from catalog_sales\n" +
                "    left join catalog_returns on cr_order_number=cs_order_number and cs_item_sk=cr_item_sk\n" +
                "    join date_dim on cs_sold_date_sk = d_date_sk\n" +
                "    where cr_order_number is null\n" +
                "    group by d_year, cs_item_sk, cs_bill_customer_sk\n" +
                "    ),\n" +
                " ss as\n" +
                "   (select d_year AS ss_sold_year, ss_item_sk,\n" +
                "     ss_customer_sk,\n" +
                "     sum(ss_quantity) ss_qty,\n" +
                "     sum(ss_wholesale_cost) ss_wc,\n" +
                "     sum(ss_sales_price) ss_sp\n" +
                "    from store_sales\n" +
                "    left join store_returns on sr_ticket_number=ss_ticket_number and ss_item_sk=sr_item_sk\n" +
                "    join date_dim on ss_sold_date_sk = d_date_sk\n" +
                "    where sr_ticket_number is null\n" +
                "    group by d_year, ss_item_sk, ss_customer_sk\n" +
                "    )\n" +
                " select\n" +
                "   ss_sold_year, ss_item_sk, ss_customer_sk,\n" +
                "   round(ss_qty/(coalesce(ws_qty,0)+coalesce(cs_qty,0)),2) ratio,\n" +
                "   ss_qty store_qty, ss_wc store_wholesale_cost, ss_sp store_sales_price,\n" +
                "   coalesce(ws_qty,0)+coalesce(cs_qty,0) other_chan_qty,\n" +
                "   coalesce(ws_wc,0)+coalesce(cs_wc,0) other_chan_wholesale_cost,\n" +
                "   coalesce(ws_sp,0)+coalesce(cs_sp,0) other_chan_sales_price\n" +
                " from ss\n" +
                " left join ws on (ws_sold_year=ss_sold_year and ws_item_sk=ss_item_sk and ws_customer_sk=ss_customer_sk)\n" +
                " left join cs on (cs_sold_year=ss_sold_year and cs_item_sk=ss_item_sk and cs_customer_sk=ss_customer_sk)\n" +
                " where (coalesce(ws_qty,0)>0 or coalesce(cs_qty, 0)>0) and ss_sold_year=2000\n" +
                " order by\n" +
                "   ss_sold_year, ss_item_sk, ss_customer_sk,\n" +
                "   ss_qty desc, ss_wc desc, ss_sp desc,\n" +
                "   other_chan_qty,\n" +
                "   other_chan_wholesale_cost,\n" +
                "   other_chan_sales_price,\n" +
                "   round(ss_qty/(coalesce(ws_qty+cs_qty,1)),2)\n" +
                "  limit 100");
        tpcdsSQLMap.put("TPCDS79", " select\n" +
                "  c_last_name,c_first_name,substr(s_city,1,30),ss_ticket_number,amt,profit\n" +
                "  from\n" +
                "   (select ss_ticket_number\n" +
                "          ,ss_customer_sk\n" +
                "          ,store.s_city\n" +
                "          ,sum(ss_coupon_amt) amt\n" +
                "          ,sum(ss_net_profit) profit\n" +
                "    from store_sales,date_dim,store,household_demographics\n" +
                "    where store_sales.ss_sold_date_sk = date_dim.d_date_sk\n" +
                "    and store_sales.ss_store_sk = store.s_store_sk\n" +
                "    and store_sales.ss_hdemo_sk = household_demographics.hd_demo_sk\n" +
                "    and (household_demographics.hd_dep_count = 6 or\n" +
                "        household_demographics.hd_vehicle_count > 2)\n" +
                "    and date_dim.d_dow = 1\n" +
                "    and date_dim.d_year in (1999,1999+1,1999+2)\n" +
                "    and store.s_number_employees between 200 and 295\n" +
                "    group by ss_ticket_number,ss_customer_sk,ss_addr_sk,store.s_city) ms,customer\n" +
                "    where ss_customer_sk = c_customer_sk\n" +
                " order by c_last_name,c_first_name,substr(s_city,1,30), profit\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS80", " with ssr as\n" +
                " (select  s_store_id as store_id,\n" +
                "          sum(ss_ext_sales_price) as sales,\n" +
                "          sum(coalesce(sr_return_amt, 0)) as returns,\n" +
                "          sum(ss_net_profit - coalesce(sr_net_loss, 0)) as profit\n" +
                "  from store_sales left outer join store_returns on\n" +
                "         (ss_item_sk = sr_item_sk and ss_ticket_number = sr_ticket_number),\n" +
                "     date_dim, store, item, promotion\n" +
                " where ss_sold_date_sk = d_date_sk\n" +
                "       and d_date between cast('2000-08-23' as date)\n" +
                "                  and (cast('2000-08-23' as date) + interval '30' day)\n" +
                "       and ss_store_sk = s_store_sk\n" +
                "       and ss_item_sk = i_item_sk\n" +
                "       and i_current_price > 50\n" +
                "       and ss_promo_sk = p_promo_sk\n" +
                "       and p_channel_tv = 'N'\n" +
                " group by s_store_id),\n" +
                " csr as\n" +
                " (select  cp_catalog_page_id as catalog_page_id,\n" +
                "          sum(cs_ext_sales_price) as sales,\n" +
                "          sum(coalesce(cr_return_amount, 0)) as returns,\n" +
                "          sum(cs_net_profit - coalesce(cr_net_loss, 0)) as profit\n" +
                "  from catalog_sales left outer join catalog_returns on\n" +
                "         (cs_item_sk = cr_item_sk and cs_order_number = cr_order_number),\n" +
                "     date_dim, catalog_page, item, promotion\n" +
                " where cs_sold_date_sk = d_date_sk\n" +
                "       and d_date between cast('2000-08-23' as date)\n" +
                "                  and (cast('2000-08-23' as date) + interval '30' day)\n" +
                "        and cs_catalog_page_sk = cp_catalog_page_sk\n" +
                "       and cs_item_sk = i_item_sk\n" +
                "       and i_current_price > 50\n" +
                "       and cs_promo_sk = p_promo_sk\n" +
                "       and p_channel_tv = 'N'\n" +
                " group by cp_catalog_page_id),\n" +
                " wsr as\n" +
                " (select  web_site_id,\n" +
                "          sum(ws_ext_sales_price) as sales,\n" +
                "          sum(coalesce(wr_return_amt, 0)) as returns,\n" +
                "          sum(ws_net_profit - coalesce(wr_net_loss, 0)) as profit\n" +
                "  from web_sales left outer join web_returns on\n" +
                "         (ws_item_sk = wr_item_sk and ws_order_number = wr_order_number),\n" +
                "     date_dim, web_site, item, promotion\n" +
                " where ws_sold_date_sk = d_date_sk\n" +
                "       and d_date between cast('2000-08-23' as date)\n" +
                "                  and (cast('2000-08-23' as date) + interval '30' day)\n" +
                "        and ws_web_site_sk = web_site_sk\n" +
                "       and ws_item_sk = i_item_sk\n" +
                "       and i_current_price > 50\n" +
                "       and ws_promo_sk = p_promo_sk\n" +
                "       and p_channel_tv = 'N'\n" +
                " group by web_site_id)\n" +
                " select channel, id, sum(sales) as sales, sum(returns) as returns, sum(profit) as profit\n" +
                " from (select\n" +
                "        'store channel' as channel, concat('store', store_id) as id, sales, returns, profit\n" +
                "      from ssr\n" +
                "      union all\n" +
                "      select\n" +
                "        'catalog channel' as channel, concat('catalog_page', catalog_page_id) as id,\n" +
                "        sales, returns, profit\n" +
                "      from csr\n" +
                "      union all\n" +
                "      select\n" +
                "        'web channel' as channel, concat('web_site', web_site_id) as id, sales, returns, profit\n" +
                "      from  wsr) x\n" +
                " group by rollup (channel, id)\n" +
                " order by channel, id\n" +
                " limit 100");

        tpcdsSQLMap.put("TPCDS81", " with customer_total_return as\n" +
                " (select\n" +
                "    cr_returning_customer_sk as ctr_customer_sk, ca_state as ctr_state,\n" +
                "        sum(cr_return_amt_inc_tax) as ctr_total_return\n" +
                " from catalog_returns, date_dim, customer_address\n" +
                " where cr_returned_date_sk = d_date_sk\n" +
                "   and d_year = 2000\n" +
                "   and cr_returning_addr_sk = ca_address_sk\n" +
                " group by cr_returning_customer_sk, ca_state )\n" +
                " select\n" +
                "    c_customer_id,c_salutation,c_first_name,c_last_name,ca_street_number,ca_street_name,\n" +
                "    ca_street_type,ca_suite_number,ca_city,ca_county,ca_state,ca_zip,ca_country,\n" +
                "    ca_gmt_offset,ca_location_type,ctr_total_return\n" +
                " from customer_total_return ctr1, customer_address, customer\n" +
                " where ctr1.ctr_total_return > (select avg(ctr_total_return)*1.2\n" +
                " \t\t\t  from customer_total_return ctr2\n" +
                "                  \t  where ctr1.ctr_state = ctr2.ctr_state)\n" +
                "       and ca_address_sk = c_current_addr_sk\n" +
                "       and ca_state = 'GA'\n" +
                "       and ctr1.ctr_customer_sk = c_customer_sk\n" +
                " order by c_customer_id,c_salutation,c_first_name,c_last_name,ca_street_number,ca_street_name\n" +
                "                   ,ca_street_type,ca_suite_number,ca_city,ca_county,ca_state,ca_zip,ca_country,ca_gmt_offset\n" +
                "                  ,ca_location_type,ctr_total_return\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS82", " select i_item_id, i_item_desc, i_current_price\n" +
                " from item, inventory, date_dim, store_sales\n" +
                " where i_current_price between 62 and 62+30\n" +
                "   and inv_item_sk = i_item_sk\n" +
                "   and d_date_sk=inv_date_sk\n" +
                "   and d_date between cast('2000-05-25' as date) and (cast('2000-05-25' as date) + interval '60' day)\n" +
                "   and i_manufact_id in (129, 270, 821, 423)\n" +
                "   and inv_quantity_on_hand between 100 and 500\n" +
                "   and ss_item_sk = i_item_sk\n" +
                " group by i_item_id,i_item_desc,i_current_price\n" +
                " order by i_item_id\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS83", " with sr_items as\n" +
                "  (select i_item_id item_id, sum(sr_return_quantity) sr_item_qty\n" +
                "   from store_returns, item, date_dim\n" +
                "   where sr_item_sk = i_item_sk\n" +
                "      and  d_date in (select d_date from date_dim where d_week_seq in\n" +
                "\t\t      (select d_week_seq from date_dim where d_date in (cast('2000-06-30' as date),cast('2000-09-27' as date),cast('2000-11-17' as date))))\n" +
                "      and sr_returned_date_sk   = d_date_sk\n" +
                "   group by i_item_id),\n" +
                " cr_items as\n" +
                "  (select i_item_id item_id, sum(cr_return_quantity) cr_item_qty\n" +
                "  from catalog_returns, item, date_dim\n" +
                "  where cr_item_sk = i_item_sk\n" +
                "      and d_date in (select d_date from date_dim where d_week_seq in\n" +
                "\t\t      (select d_week_seq from date_dim where d_date in (cast('2000-06-30' as date),cast('2000-09-27' as date),cast('2000-11-17' as date))))\n" +
                "      and cr_returned_date_sk   = d_date_sk\n" +
                "      group by i_item_id),\n" +
                " wr_items as\n" +
                "  (select i_item_id item_id, sum(wr_return_quantity) wr_item_qty\n" +
                "  from web_returns, item, date_dim\n" +
                "  where wr_item_sk = i_item_sk and d_date in\n" +
                "      (select d_date\tfrom date_dim\twhere d_week_seq in\n" +
                "\t\t      (select d_week_seq from date_dim where d_date in (cast('2000-06-30' as date),cast('2000-09-27' as date),cast('2000-11-17' as date))))\n" +
                "    and wr_returned_date_sk = d_date_sk\n" +
                "  group by i_item_id)\n" +
                " select sr_items.item_id\n" +
                "       ,sr_item_qty\n" +
                "       ,sr_item_qty/(sr_item_qty+cr_item_qty+wr_item_qty)/3.0 * 100 sr_dev\n" +
                "       ,cr_item_qty\n" +
                "       ,cr_item_qty/(sr_item_qty+cr_item_qty+wr_item_qty)/3.0 * 100 cr_dev\n" +
                "       ,wr_item_qty\n" +
                "       ,wr_item_qty/(sr_item_qty+cr_item_qty+wr_item_qty)/3.0 * 100 wr_dev\n" +
                "       ,(sr_item_qty+cr_item_qty+wr_item_qty)/3.0 average\n" +
                " from sr_items, cr_items, wr_items\n" +
                " where sr_items.item_id=cr_items.item_id\n" +
                "   and sr_items.item_id=wr_items.item_id\n" +
                " order by sr_items.item_id, sr_item_qty\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS84", "select c_customer_id as customer_id, concat('aa',',','bb')\n" +
                "       from customer\n" +
                "     ,customer_address\n" +
                "     ,customer_demographics\n" +
                "     ,household_demographics\n" +
                "     ,income_band\n" +
                "     ,store_returns\n" +
                " where ca_city\t        =  'Edgewood'\n" +
                "   and c_current_addr_sk = ca_address_sk\n" +
                "   and ib_lower_bound   >=  38128\n" +
                "   and ib_upper_bound   <=  38128 + 50000\n" +
                "   and ib_income_band_sk = hd_income_band_sk\n" +
                "   and cd_demo_sk = c_current_cdemo_sk\n" +
                "   and hd_demo_sk = c_current_hdemo_sk\n" +
                "   and sr_cdemo_sk = cd_demo_sk\n" +
                " order by c_customer_id\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS85", " select\n" +
                "    substr(r_reason_desc,1,20), avg(ws_quantity), avg(wr_refunded_cash), avg(wr_fee)\n" +
                " from web_sales, web_returns, web_page, customer_demographics cd1,\n" +
                "      customer_demographics cd2, customer_address, date_dim, reason\n" +
                " where ws_web_page_sk = wp_web_page_sk\n" +
                "   and ws_item_sk = wr_item_sk\n" +
                "   and ws_order_number = wr_order_number\n" +
                "   and ws_sold_date_sk = d_date_sk and d_year = 2000\n" +
                "   and cd1.cd_demo_sk = wr_refunded_cdemo_sk\n" +
                "   and cd2.cd_demo_sk = wr_returning_cdemo_sk\n" +
                "   and ca_address_sk = wr_refunded_addr_sk\n" +
                "   and r_reason_sk = wr_reason_sk\n" +
                "   and\n" +
                "   (\n" +
                "    (\n" +
                "     cd1.cd_marital_status = 'M'\n" +
                "     and\n" +
                "     cd1.cd_marital_status = cd2.cd_marital_status\n" +
                "     and\n" +
                "     cd1.cd_education_status = 'Advanced Degree'\n" +
                "     and\n" +
                "     cd1.cd_education_status = cd2.cd_education_status\n" +
                "     and\n" +
                "     ws_sales_price between 100.00 and 150.00\n" +
                "    )\n" +
                "   or\n" +
                "    (\n" +
                "     cd1.cd_marital_status = 'S'\n" +
                "     and\n" +
                "     cd1.cd_marital_status = cd2.cd_marital_status\n" +
                "     and\n" +
                "     cd1.cd_education_status = 'College'\n" +
                "     and\n" +
                "     cd1.cd_education_status = cd2.cd_education_status\n" +
                "     and\n" +
                "     ws_sales_price between 50.00 and 100.00\n" +
                "    )\n" +
                "   or\n" +
                "    (\n" +
                "     cd1.cd_marital_status = 'W'\n" +
                "     and\n" +
                "     cd1.cd_marital_status = cd2.cd_marital_status\n" +
                "     and\n" +
                "     cd1.cd_education_status = '2 yr Degree'\n" +
                "     and\n" +
                "     cd1.cd_education_status = cd2.cd_education_status\n" +
                "     and\n" +
                "     ws_sales_price between 150.00 and 200.00\n" +
                "    )\n" +
                "   )\n" +
                "   and\n" +
                "   (\n" +
                "    (\n" +
                "     ca_country = 'United States'\n" +
                "     and\n" +
                "     ca_state in ('IN', 'OH', 'NJ')\n" +
                "     and ws_net_profit between 100 and 200\n" +
                "    )\n" +
                "    or\n" +
                "    (\n" +
                "     ca_country = 'United States'\n" +
                "     and\n" +
                "     ca_state in ('WI', 'CT', 'KY')\n" +
                "     and ws_net_profit between 150 and 300\n" +
                "    )\n" +
                "    or\n" +
                "    (\n" +
                "     ca_country = 'United States'\n" +
                "     and\n" +
                "     ca_state in ('LA', 'IA', 'AR')\n" +
                "     and ws_net_profit between 50 and 250\n" +
                "    )\n" +
                "   )\n" +
                " group by r_reason_desc\n" +
                " order by substr(r_reason_desc,1,20)\n" +
                "        ,avg(ws_quantity)\n" +
                "        ,avg(wr_refunded_cash)\n" +
                "        ,avg(wr_fee)\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS86", " select sum(ws_net_paid) as total_sum, i_category, i_class,\n" +
                "  grouping(i_category)+grouping(i_class) as lochierarchy,\n" +
                "  rank() over (\n" +
                " \t    partition by grouping(i_category)+grouping(i_class),\n" +
                " \t    case when grouping(i_class) = 0 then i_category end\n" +
                " \t    order by sum(ws_net_paid) desc) as rank_within_parent\n" +
                " from\n" +
                "    web_sales, date_dim d1, item\n" +
                " where\n" +
                "    d1.d_month_seq between 1200 and 1200+11\n" +
                " and d1.d_date_sk = ws_sold_date_sk\n" +
                " and i_item_sk  = ws_item_sk\n" +
                " group by rollup(i_category,i_class)\n" +
                " order by\n" +
                "   lochierarchy desc,\n" +
                "   case when lochierarchy = 0 then i_category end,\n" +
                "   rank_within_parent\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS87", "select count(*)\n" +
                " from ((select distinct c_last_name, c_first_name, d_date\n" +
                "       from store_sales, date_dim, customer\n" +
                "       where store_sales.ss_sold_date_sk = date_dim.d_date_sk\n" +
                "         and store_sales.ss_customer_sk = customer.c_customer_sk\n" +
                "         and d_month_seq between 1200 and 1200+11)\n" +
                "       except\n" +
                "      (select distinct c_last_name, c_first_name, d_date\n" +
                "       from catalog_sales, date_dim, customer\n" +
                "       where catalog_sales.cs_sold_date_sk = date_dim.d_date_sk\n" +
                "         and catalog_sales.cs_bill_customer_sk = customer.c_customer_sk\n" +
                "         and d_month_seq between 1200 and 1200+11)\n" +
                "       except\n" +
                "      (select distinct c_last_name, c_first_name, d_date\n" +
                "       from web_sales, date_dim, customer\n" +
                "       where web_sales.ws_sold_date_sk = date_dim.d_date_sk\n" +
                "         and web_sales.ws_bill_customer_sk = customer.c_customer_sk\n" +
                "         and d_month_seq between 1200 and 1200+11)\n" +
                ") cool_cust");
        tpcdsSQLMap.put("TPCDS88", "select  *\n" +
                " from\n" +
                "   (select count(*) h8_30_to_9\n" +
                "    from store_sales, household_demographics , time_dim, store\n" +
                "    where ss_sold_time_sk = time_dim.t_time_sk\n" +
                "     and ss_hdemo_sk = household_demographics.hd_demo_sk\n" +
                "     and ss_store_sk = s_store_sk\n" +
                "     and time_dim.t_hour = 8\n" +
                "     and time_dim.t_minute >= 30\n" +
                "     and ((household_demographics.hd_dep_count = 4 and household_demographics.hd_vehicle_count<=4+2) or\n" +
                "          (household_demographics.hd_dep_count = 2 and household_demographics.hd_vehicle_count<=2+2) or\n" +
                "          (household_demographics.hd_dep_count = 0 and household_demographics.hd_vehicle_count<=0+2))\n" +
                "     and store.s_store_name = 'ese') s1 cross join\n" +
                "   (select count(*) h9_to_9_30\n" +
                "    from store_sales, household_demographics , time_dim, store\n" +
                "    where ss_sold_time_sk = time_dim.t_time_sk\n" +
                "      and ss_hdemo_sk = household_demographics.hd_demo_sk\n" +
                "      and ss_store_sk = s_store_sk\n" +
                "      and time_dim.t_hour = 9\n" +
                "      and time_dim.t_minute < 30\n" +
                "      and ((household_demographics.hd_dep_count = 4 and household_demographics.hd_vehicle_count<=4+2) or\n" +
                "          (household_demographics.hd_dep_count = 2 and household_demographics.hd_vehicle_count<=2+2) or\n" +
                "          (household_demographics.hd_dep_count = 0 and household_demographics.hd_vehicle_count<=0+2))\n" +
                "      and store.s_store_name = 'ese') s2 cross join\n" +
                " (select count(*) h9_30_to_10\n" +
                " from store_sales, household_demographics , time_dim, store\n" +
                " where ss_sold_time_sk = time_dim.t_time_sk\n" +
                "     and ss_hdemo_sk = household_demographics.hd_demo_sk\n" +
                "     and ss_store_sk = s_store_sk\n" +
                "     and time_dim.t_hour = 9\n" +
                "     and time_dim.t_minute >= 30\n" +
                "     and ((household_demographics.hd_dep_count = 4 and household_demographics.hd_vehicle_count<=4+2) or\n" +
                "          (household_demographics.hd_dep_count = 2 and household_demographics.hd_vehicle_count<=2+2) or\n" +
                "          (household_demographics.hd_dep_count = 0 and household_demographics.hd_vehicle_count<=0+2))\n" +
                "     and store.s_store_name = 'ese') s3 cross join\n" +
                " (select count(*) h10_to_10_30\n" +
                " from store_sales, household_demographics , time_dim, store\n" +
                " where ss_sold_time_sk = time_dim.t_time_sk\n" +
                "     and ss_hdemo_sk = household_demographics.hd_demo_sk\n" +
                "     and ss_store_sk = s_store_sk\n" +
                "     and time_dim.t_hour = 10\n" +
                "     and time_dim.t_minute < 30\n" +
                "     and ((household_demographics.hd_dep_count = 4 and household_demographics.hd_vehicle_count<=4+2) or\n" +
                "          (household_demographics.hd_dep_count = 2 and household_demographics.hd_vehicle_count<=2+2) or\n" +
                "          (household_demographics.hd_dep_count = 0 and household_demographics.hd_vehicle_count<=0+2))\n" +
                "     and store.s_store_name = 'ese') s4 cross join\n" +
                " (select count(*) h10_30_to_11\n" +
                " from store_sales, household_demographics , time_dim, store\n" +
                " where ss_sold_time_sk = time_dim.t_time_sk\n" +
                "     and ss_hdemo_sk = household_demographics.hd_demo_sk\n" +
                "     and ss_store_sk = s_store_sk\n" +
                "     and time_dim.t_hour = 10\n" +
                "     and time_dim.t_minute >= 30\n" +
                "     and ((household_demographics.hd_dep_count = 4 and household_demographics.hd_vehicle_count<=4+2) or\n" +
                "          (household_demographics.hd_dep_count = 2 and household_demographics.hd_vehicle_count<=2+2) or\n" +
                "          (household_demographics.hd_dep_count = 0 and household_demographics.hd_vehicle_count<=0+2))\n" +
                "     and store.s_store_name = 'ese') s5 cross join\n" +
                " (select count(*) h11_to_11_30\n" +
                " from store_sales, household_demographics , time_dim, store\n" +
                " where ss_sold_time_sk = time_dim.t_time_sk\n" +
                "     and ss_hdemo_sk = household_demographics.hd_demo_sk\n" +
                "     and ss_store_sk = s_store_sk\n" +
                "     and time_dim.t_hour = 11\n" +
                "     and time_dim.t_minute < 30\n" +
                "     and ((household_demographics.hd_dep_count = 4 and household_demographics.hd_vehicle_count<=4+2) or\n" +
                "          (household_demographics.hd_dep_count = 2 and household_demographics.hd_vehicle_count<=2+2) or\n" +
                "          (household_demographics.hd_dep_count = 0 and household_demographics.hd_vehicle_count<=0+2))\n" +
                "     and store.s_store_name = 'ese') s6 cross join\n" +
                " (select count(*) h11_30_to_12\n" +
                " from store_sales, household_demographics , time_dim, store\n" +
                " where ss_sold_time_sk = time_dim.t_time_sk\n" +
                "     and ss_hdemo_sk = household_demographics.hd_demo_sk\n" +
                "     and ss_store_sk = s_store_sk\n" +
                "     and time_dim.t_hour = 11\n" +
                "     and time_dim.t_minute >= 30\n" +
                "     and ((household_demographics.hd_dep_count = 4 and household_demographics.hd_vehicle_count<=4+2) or\n" +
                "          (household_demographics.hd_dep_count = 2 and household_demographics.hd_vehicle_count<=2+2) or\n" +
                "          (household_demographics.hd_dep_count = 0 and household_demographics.hd_vehicle_count<=0+2))\n" +
                "     and store.s_store_name = 'ese') s7 cross join\n" +
                " (select count(*) h12_to_12_30\n" +
                " from store_sales, household_demographics , time_dim, store\n" +
                " where ss_sold_time_sk = time_dim.t_time_sk\n" +
                "     and ss_hdemo_sk = household_demographics.hd_demo_sk\n" +
                "     and ss_store_sk = s_store_sk\n" +
                "     and time_dim.t_hour = 12\n" +
                "     and time_dim.t_minute < 30\n" +
                "     and ((household_demographics.hd_dep_count = 4 and household_demographics.hd_vehicle_count<=4+2) or\n" +
                "          (household_demographics.hd_dep_count = 2 and household_demographics.hd_vehicle_count<=2+2) or\n" +
                "          (household_demographics.hd_dep_count = 0 and household_demographics.hd_vehicle_count<=0+2))\n" +
                "     and store.s_store_name = 'ese') s8");
        tpcdsSQLMap.put("TPCDS89", " select *\n" +
                " from(\n" +
                " select i_category, i_class, i_brand,\n" +
                "       s_store_name, s_company_name,\n" +
                "       d_moy,\n" +
                "       sum(ss_sales_price) sum_sales,\n" +
                "       avg(sum(ss_sales_price)) over\n" +
                "         (partition by i_category, i_brand, s_store_name, s_company_name)\n" +
                "         avg_monthly_sales\n" +
                " from item, store_sales, date_dim, store\n" +
                " where ss_item_sk = i_item_sk and\n" +
                "      ss_sold_date_sk = d_date_sk and\n" +
                "      ss_store_sk = s_store_sk and\n" +
                "      d_year in (1999) and\n" +
                "       ((i_category in ('Books','Electronics','Sports') and\n" +
                "          i_class in ('computers','stereo','football'))\n" +
                "      or (i_category in ('Men','Jewelry','Women') and\n" +
                "           i_class in ('shirts','birdal','dresses')))\n" +
                " group by i_category, i_class, i_brand,\n" +
                "         s_store_name, s_company_name, d_moy) tmp1\n" +
                " where case when (avg_monthly_sales <> 0) then (abs(sum_sales - avg_monthly_sales) / avg_monthly_sales) else null end > 0.1\n" +
                " order by sum_sales - avg_monthly_sales, s_store_name\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS90", "select cast(amc as decimal(15,4))/cast(pmc as decimal(15,4)) am_pm_ratio\n" +
                " from ( select count(*) amc\n" +
                "       from web_sales, household_demographics , time_dim, web_page\n" +
                "       where ws_sold_time_sk = time_dim.t_time_sk\n" +
                "         and ws_ship_hdemo_sk = household_demographics.hd_demo_sk\n" +
                "         and ws_web_page_sk = web_page.wp_web_page_sk\n" +
                "         and time_dim.t_hour between 8 and 8+1\n" +
                "         and household_demographics.hd_dep_count = 6\n" +
                "         and web_page.wp_char_count between 5000 and 5200) at cross join\n" +
                "      ( select count(*) pmc\n" +
                "       from web_sales, household_demographics , time_dim, web_page\n" +
                "       where ws_sold_time_sk = time_dim.t_time_sk\n" +
                "         and ws_ship_hdemo_sk = household_demographics.hd_demo_sk\n" +
                "         and ws_web_page_sk = web_page.wp_web_page_sk\n" +
                "         and time_dim.t_hour between 19 and 19+1\n" +
                "         and household_demographics.hd_dep_count = 6\n" +
                "         and web_page.wp_char_count between 5000 and 5200) pt\n" +
                " order by am_pm_ratio\n" +
                " limit 100");

        tpcdsSQLMap.put("TPCDS91", "select\n" +
                "        cc_call_center_id Call_Center, cc_name Call_Center_Name, cc_manager Manager,\n" +
                "        sum(cr_net_loss) Returns_Loss\n" +
                " from\n" +
                "        call_center, catalog_returns, date_dim, customer, customer_address,\n" +
                "        customer_demographics, household_demographics\n" +
                " where\n" +
                "        cr_call_center_sk        = cc_call_center_sk\n" +
                " and    cr_returned_date_sk      = d_date_sk\n" +
                " and    cr_returning_customer_sk = c_customer_sk\n" +
                " and    cd_demo_sk               = c_current_cdemo_sk\n" +
                " and    hd_demo_sk               = c_current_hdemo_sk\n" +
                " and    ca_address_sk            = c_current_addr_sk\n" +
                " and    d_year                   = 1998\n" +
                " and    d_moy                    = 11\n" +
                " and    ( (cd_marital_status     = 'M' and cd_education_status = 'Unknown')\n" +
                "        or(cd_marital_status     = 'W' and cd_education_status = 'Advanced Degree'))\n" +
                " and    hd_buy_potential like 'Unknown%'\n" +
                " and    ca_gmt_offset            = -7\n" +
                " group by cc_call_center_id,cc_name,cc_manager,cd_marital_status,cd_education_status\n" +
                " order by sum(cr_net_loss) desc");
        tpcdsSQLMap.put("TPCDS92", " select sum(ws_ext_discount_amt) as Excess_Discount_Amount\n" +
                " from web_sales, item, date_dim\n" +
                " where i_manufact_id = 350\n" +
                " and i_item_sk = ws_item_sk\n" +
                " and d_date between cast ('2000-01-27' as date) and (cast('2000-01-27' as date) + interval '90' day)\n" +
                " and d_date_sk = ws_sold_date_sk\n" +
                " and ws_ext_discount_amt >\n" +
                "     (\n" +
                "       SELECT 1.3 * avg(ws_ext_discount_amt)\n" +
                "       FROM web_sales, date_dim\n" +
                "       WHERE ws_item_sk = i_item_sk\n" +
                "         and d_date between cast ('2000-01-27' as date) and (cast('2000-01-27' as date) + interval '90' day)\n" +
                "         and d_date_sk = ws_sold_date_sk\n" +
                "     )\n" +
                " order by sum(ws_ext_discount_amt)\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS93", "select ss_customer_sk, sum(act_sales) sumsales\n" +
                " from (select\n" +
                "         ss_item_sk, ss_ticket_number, ss_customer_sk,\n" +
                "         case when sr_return_quantity is not null then (ss_quantity-sr_return_quantity)*ss_sales_price\n" +
                "                                                  else (ss_quantity*ss_sales_price) end act_sales\n" +
                "       from store_sales\n" +
                "       left outer join store_returns\n" +
                "       on (sr_item_sk = ss_item_sk and sr_ticket_number = ss_ticket_number),\n" +
                "       reason\n" +
                "       where sr_reason_sk = r_reason_sk and r_reason_desc = 'reason 28') t\n" +
                " group by ss_customer_sk\n" +
                " order by sumsales, ss_customer_sk\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS94", "select\n" +
                "    count(distinct ws_order_number) as order_count\n" +
                "   ,sum(ws_ext_ship_cost) as total_shipping_cost\n" +
                "   ,sum(ws_net_profit) as total_net_profit\n" +
                " from\n" +
                "    web_sales ws1, date_dim, customer_address, web_site\n" +
                " where\n" +
                "     d_date between cast('1999-02-01' as date) and\n" +
                "            (cast('1999-02-01' as date) + interval '60' day)\n" +
                " and ws1.ws_ship_date_sk = d_date_sk\n" +
                " and ws1.ws_ship_addr_sk = ca_address_sk\n" +
                " and ca_state = 'IL'\n" +
                " and ws1.ws_web_site_sk = web_site_sk\n" +
                " and web_company_name = 'pri'\n" +
                " and exists (select *\n" +
                "             from web_sales ws2\n" +
                "             where ws1.ws_order_number = ws2.ws_order_number\n" +
                "               and ws1.ws_warehouse_sk <> ws2.ws_warehouse_sk)\n" +
                " and not exists(select *\n" +
                "                from web_returns wr1\n" +
                "                where ws1.ws_order_number = wr1.wr_order_number)\n" +
                " order by count(distinct ws_order_number)\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS95", " with ws_wh as\n" +
                " (select ws1.ws_order_number,ws1.ws_warehouse_sk wh1,ws2.ws_warehouse_sk wh2\n" +
                "  from web_sales ws1,web_sales ws2\n" +
                "  where ws1.ws_order_number = ws2.ws_order_number\n" +
                "    and ws1.ws_warehouse_sk <> ws2.ws_warehouse_sk)\n" +
                " select\n" +
                "    count(distinct ws_order_number) as order_count\n" +
                "   ,sum(ws_ext_ship_cost) as total_shipping_cost\n" +
                "   ,sum(ws_net_profit) as total_net_profit\n" +
                " from\n" +
                "    web_sales ws1, date_dim, customer_address, web_site\n" +
                " where\n" +
                "     d_date between cast ('1999-02-01' as date) and\n" +
                "            (cast('1999-02-01' as date) + interval '60' day)\n" +
                " and ws1.ws_ship_date_sk = d_date_sk\n" +
                " and ws1.ws_ship_addr_sk = ca_address_sk\n" +
                " and ca_state = 'IL'\n" +
                " and ws1.ws_web_site_sk = web_site_sk\n" +
                " and web_company_name = 'pri'\n" +
                " and ws1.ws_order_number in (select ws_order_number\n" +
                "                             from ws_wh)\n" +
                " and ws1.ws_order_number in (select wr_order_number\n" +
                "                             from web_returns,ws_wh\n" +
                "                             where wr_order_number = ws_wh.ws_order_number)\n" +
                " order by count(distinct ws_order_number)\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS96", " select count(*)\n" +
                " from store_sales, household_demographics, time_dim, store\n" +
                " where ss_sold_time_sk = time_dim.t_time_sk\n" +
                "     and ss_hdemo_sk = household_demographics.hd_demo_sk\n" +
                "     and ss_store_sk = s_store_sk\n" +
                "     and time_dim.t_hour = 20\n" +
                "     and time_dim.t_minute >= 30\n" +
                "     and household_demographics.hd_dep_count = 7\n" +
                "     and store.s_store_name = 'ese'\n" +
                " order by count(*)\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS97", " with ssci as (\n" +
                " select ss_customer_sk customer_sk, ss_item_sk item_sk\n" +
                " from store_sales,date_dim\n" +
                " where ss_sold_date_sk = d_date_sk\n" +
                "   and d_month_seq between 1200 and 1200 + 11\n" +
                " group by ss_customer_sk, ss_item_sk),\n" +
                " csci as(\n" +
                "  select cs_bill_customer_sk customer_sk, cs_item_sk item_sk\n" +
                " from catalog_sales,date_dim\n" +
                " where cs_sold_date_sk = d_date_sk\n" +
                "   and d_month_seq between 1200 and 1200 + 11\n" +
                " group by cs_bill_customer_sk, cs_item_sk)\n" +
                " select sum(case when ssci.customer_sk is not null and csci.customer_sk is null then 1 else 0 end) store_only\n" +
                "       ,sum(case when ssci.customer_sk is null and csci.customer_sk is not null then 1 else 0 end) catalog_only\n" +
                "       ,sum(case when ssci.customer_sk is not null and csci.customer_sk is not null then 1 else 0 end) store_and_catalog\n" +
                " from ssci full outer join csci on (ssci.customer_sk=csci.customer_sk\n" +
                "                                and ssci.item_sk = csci.item_sk)\n" +
                " limit 100");
        tpcdsSQLMap.put("TPCDS98", "select i_item_desc, i_category, i_class, i_current_price\n" +
                "      ,sum(ss_ext_sales_price) as itemrevenue\n" +
                "      ,sum(ss_ext_sales_price)*100/sum(sum(ss_ext_sales_price)) over\n" +
                "          (partition by i_class) as revenueratio\n" +
                "from\n" +
                "\t store_sales, item, date_dim\n" +
                "where\n" +
                "\tss_item_sk = i_item_sk\n" +
                "  \tand i_category in ('Sports', 'Books', 'Home')\n" +
                "  \tand ss_sold_date_sk = d_date_sk\n" +
                "\tand d_date between cast('1999-02-22' as date)\n" +
                "\t\t\t\tand (cast('1999-02-22' as date) + interval '30' day)\n" +
                "group by\n" +
                "\ti_item_id, i_item_desc, i_category, i_class, i_current_price\n" +
                "order by\n" +
                "\ti_category, i_class, i_item_id, i_item_desc, revenueratio");
        tpcdsSQLMap.put("TPCDS99", " select\n" +
                "    substr(w_warehouse_name,1,20), sm_type, cc_name\n" +
                "   ,sum(case when (cs_ship_date_sk - cs_sold_date_sk <= 30 ) then 1 else 0 end)  as days\n" +
                "   ,sum(case when (cs_ship_date_sk - cs_sold_date_sk > 30) and\n" +
                "                  (cs_ship_date_sk - cs_sold_date_sk <= 60) then 1 else 0 end )  as days3\n" +
                "   ,sum(case when (cs_ship_date_sk - cs_sold_date_sk > 60) and\n" +
                "                  (cs_ship_date_sk - cs_sold_date_sk <= 90) then 1 else 0 end)  as days6\n" +
                "   ,sum(case when (cs_ship_date_sk - cs_sold_date_sk > 90) and\n" +
                "                  (cs_ship_date_sk - cs_sold_date_sk <= 120) then 1 else 0 end)  as days9\n" +
                "   ,sum(case when (cs_ship_date_sk - cs_sold_date_sk  > 120) then 1 else 0 end)  as days120\n" +
                " from\n" +
                "    catalog_sales, warehouse, ship_mode, call_center, date_dim\n" +
                " where\n" +
                "     d_month_seq between 1200 and 1200 + 11\n" +
                " and cs_ship_date_sk   = d_date_sk\n" +
                " and cs_warehouse_sk   = w_warehouse_sk\n" +
                " and cs_ship_mode_sk   = sm_ship_mode_sk\n" +
                " and cs_call_center_sk = cc_call_center_sk\n" +
                " group by\n" +
                "    substr(w_warehouse_name,1,20), sm_type, cc_name\n" +
                " order by substr(w_warehouse_name,1,20), sm_type, cc_name\n" +
                " limit 100");
        return tpcdsSQLMap;
    }

}
