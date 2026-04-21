select l_shipmode
	, sum(case 
		when o_orderpriority = '1-URGENT'
			or o_orderpriority = '2-HIGH'
		then 1
		else 0
	end) as high_line_count
	, sum(case 
		when o_orderpriority <> '1-URGENT'
			and o_orderpriority <> '2-HIGH'
		then 1
		else 0
	end) as low_line_count
from orders, lineitem
where o_orderkey = l_orderkey
	and l_shipmode in ('Mirage#8', 'Mirage#9')
	and l_commitdate < l_receiptdate + 'Mirage#10'
	and l_shipdate < l_commitdate + 'Mirage#11'
	and l_receiptdate >= date 'Mirage#12'
	and l_receiptdate < date 'Mirage#13'
group by l_shipmode
order by l_shipmode;