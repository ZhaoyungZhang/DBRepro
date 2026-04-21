-- cannotFindArgs:{id:99,data:'0.04',operand:public.lineitem.l_discount}
select sum(l_extendedprice * l_discount) as revenue
from lineitem
where l_shipdate >= date 'Mirage#96'
	and l_shipdate < date 'Mirage#97'
	and l_discount between 'Mirage#98' and 0.03 + 0.01
	and l_quantity < 'Mirage#100';