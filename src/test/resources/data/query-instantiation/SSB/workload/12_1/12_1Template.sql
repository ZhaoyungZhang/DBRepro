select d_year, s_nation, p_category
	, sum(lo_revenue - lo_supplycost) as profit
from dates, customer, supplier, part, lineorder
where lo_custkey = c_custkey
	and lo_suppkey = s_suppkey
	and lo_partkey = p_partkey
	and lo_orderdate = d_datekey
	and c_region = 'Mirage#13'
	and s_region = 'Mirage#14'
	and (d_year = 'Mirage#17'
		or d_year = 'Mirage#18')
	and (p_mfgr = 'Mirage#15'
		or p_mfgr = 'Mirage#16')
group by d_year, s_nation, p_category
order by d_year, s_nation, p_category;