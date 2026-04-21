select sum(lo_extendedprice * lo_discount) as revenue
from lineorder, dates
where lo_orderdate = d_datekey
	and d_weeknuminyear = 'Mirage#28'
	and d_year = 'Mirage#29'
	and lo_discount between 'Mirage#30' and 'Mirage#31'
	and lo_quantity between 'Mirage#32' and 'Mirage#33';