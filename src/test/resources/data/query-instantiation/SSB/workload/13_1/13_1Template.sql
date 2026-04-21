select d_year, s_city, p_brand
	, sum(lo_revenue - lo_supplycost) as profit
from dates, customer, supplier, part, lineorder
where lo_custkey = c_custkey
	and lo_suppkey = s_suppkey
	and lo_partkey = p_partkey
	and lo_orderdate = d_datekey
	and s_nation = 'Mirage#20'
	and (d_year = 'Mirage#21'
		or d_year = 'Mirage#22')
	and p_category = 'Mirage#19'
group by d_year, s_city, p_brand
order by d_year, s_city, p_brand;