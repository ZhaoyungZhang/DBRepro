select c_city, s_city, d_year, sum(lo_revenue) as revenue
from customer, lineorder, supplier, dates
where lo_custkey = c_custkey
	and lo_suppkey = s_suppkey
	and lo_orderdate = d_datekey
	and (c_city = 'Mirage#51'
		or c_city = 'Mirage#52')
	and (s_city = 'Mirage#49'
		or s_city = 'Mirage#50')
	and d_year >= 'Mirage#53'
	and d_year <= 'Mirage#54'
group by c_city, s_city, d_year
order by d_year asc, revenue desc;