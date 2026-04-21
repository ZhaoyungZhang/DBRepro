select c_nation, s_nation, d_year, sum(lo_revenue) as revenue
from customer, lineorder, supplier, dates
where lo_custkey = c_custkey
	and lo_suppkey = s_suppkey
	and lo_orderdate = d_datekey
	and c_region = 'Mirage#42'
	and s_region = 'Mirage#41'
	and d_year >= 'Mirage#43'
	and d_year <= 'Mirage#44'
group by c_nation, s_nation, d_year
order by d_year asc, revenue desc;