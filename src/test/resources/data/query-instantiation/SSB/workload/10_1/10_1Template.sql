select c_city, s_city, d_year, sum(lo_revenue) as revenue
from customer, lineorder, supplier, dates
where lo_custkey = c_custkey
	and lo_suppkey = s_suppkey
	and lo_orderdate = d_datekey
	and (c_city = 'Mirage#7'
		or c_city = 'Mirage#8')
	and (s_city = 'Mirage#5'
		or s_city = 'Mirage#6')
	and d_yearmonth = 'Mirage#4'
group by c_city, s_city, d_year
order by d_year asc, revenue desc;