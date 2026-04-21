-- cannotFindArgs:{id:55,data:'8',operand:public.lineitem.l_quantity}
select sum(l_extendedprice * (1 - l_discount)) as revenue
from lineitem, part
where (p_partkey = l_partkey
		and p_brand = 'Mirage#34'
		and p_container in ('Mirage#35', 'Mirage#36', 'Mirage#37', 'Mirage#38')
		and l_quantity >= 8
		and l_quantity <= 'Mirage#56'
		and p_size between 'Mirage#33' and 'Mirage#39'
		and l_shipmode in ('Mirage#52', 'Mirage#53')
		and l_shipinstruct = 'Mirage#54')
	or (p_partkey = l_partkey
		and p_brand = 'Mirage#40'
		and p_container in ('Mirage#41', 'Mirage#42', 'Mirage#43', 'Mirage#44')
		and l_quantity >= 'Mirage#57'
		and l_quantity <= 'Mirage#58'
		and p_size between 'Mirage#33' and 'Mirage#45'
		and l_shipmode in ('Mirage#52', 'Mirage#53')
		and l_shipinstruct = 'Mirage#54')
	or (p_partkey = l_partkey
		and p_brand = 'Mirage#46'
		and p_container in ('Mirage#47', 'Mirage#48', 'Mirage#49', 'Mirage#50')
		and l_quantity >= 'Mirage#59'
		and l_quantity <= 'Mirage#60'
		and p_size between 'Mirage#33' and 'Mirage#51'
		and l_shipmode in ('Mirage#52', 'Mirage#53')
		and l_shipinstruct = 'Mirage#54');