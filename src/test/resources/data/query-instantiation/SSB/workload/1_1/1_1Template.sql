select sum(lo_extendedprice * lo_discount) as revenue
from lineorder, dates
where lo_orderdate = d_datekey
	and d_year = 'Mirage#0'
	and lo_discount between 'Mirage#1' and 'Mirage#2'
	and lo_quantity < 'Mirage#3';