select nation, o_year, sum(amount) as sum_profit
from (
	select n_name as nation, extract(year from o_orderdate) as o_year
		, l_extendedprice * (1 - l_discount) - ps_supplycost * l_quantity as amount
	from partsupp
		cross join supplier
		cross join part, lineitem, orders, nation
	where s_suppkey = ps_suppkey
		and ps_partkey = l_partkey
		and ps_suppkey = l_suppkey
		and p_partkey = ps_partkey
		and o_orderkey = l_orderkey
		and s_nationkey = n_nationkey
		and p_name like 'Mirage#111'
) profit
group by nation, o_year
order by nation, o_year desc;