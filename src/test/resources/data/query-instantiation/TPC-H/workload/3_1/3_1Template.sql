select l_orderkey, sum(l_extendedprice * (1 - l_discount)) as revenue
	, o_orderdate, o_shippriority
from customer, orders, lineitem
where c_mktsegment = 'Mirage#89'
	and c_custkey = o_custkey
	and l_orderkey = o_orderkey
	and o_orderdate < date 'Mirage#87'
	and l_shipdate > date 'Mirage#87'
group by l_orderkey, o_orderdate, o_shippriority
order by revenue desc, o_orderdate;