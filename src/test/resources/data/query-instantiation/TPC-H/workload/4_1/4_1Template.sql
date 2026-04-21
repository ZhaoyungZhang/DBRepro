select o_orderpriority, count(*) as order_count
from orders
where o_orderdate >= date 'Mirage#91'
	and o_orderdate < date 'Mirage#92'
	and exists (
		select *
		from lineitem
		where l_orderkey = o_orderkey
			and l_commitdate < l_receiptdate + 'Mirage#90'
	)
group by o_orderpriority
order by o_orderpriority;