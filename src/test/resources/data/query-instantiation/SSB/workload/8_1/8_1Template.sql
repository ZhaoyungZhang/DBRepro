select c_city, s_city, d_year, sum(lo_revenue) as revenue
from customer, lineorder, supplier, dates
where lo_custkey = c_custkey
	and lo_suppkey = s_suppkey
	and lo_orderdate = d_datekey
	and c_nation = 'Mirage#45'
	and s_nation = 'Mirage#48'
	and d_year >= 'Mirage#46'
	and d_year <= 'Mirage#47'
group by c_city, s_city, d_year
order by d_year asc, revenue desc;