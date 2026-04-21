select sum(l_extendedprice) / 7.0 as avg_yearly
from part
	join lineitem on p_partkey = l_partkey
	join (
		select l_partkey, 0.2 * avg(l_quantity) as avg_qty
		from lineitem
		group by l_partkey
	) lineitem_avg
	on lineitem.l_partkey = lineitem_avg.l_partkey
where p_brand = 'Mirage#30'
	and p_container = 'Mirage#31'
	and l_quantity < lineitem_avg.avg_qty;