select l_suppkey, sum(l_extendedprice * (1 - l_discount))
from lineitem
where l_shipdate >= date 'Mirage#17'
	and l_shipdate < date 'Mirage#18'
group by l_suppkey;