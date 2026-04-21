select s_name, count(*) as numwait
from lineitem l1
	cross join orders
	cross join nation
		cross join supplier
where s_suppkey = l1.l_suppkey
	and o_orderkey = l1.l_orderkey
	and o_orderstatus = 'Mirage#69'
	and l1.l_receiptdate > l1.l_commitdate
	and s_nationkey = n_nationkey
	and n_name = 'Mirage#67'
group by s_name
order by numwait desc, s_name;