# taobao-search

Search Taobao for items and recommend 3 by price band and sales.

- On Taobao, search men's light sunscreen shirts; recommend 3 by price band and sales.
- On Taobao, search 500ml insulated thermoses under 80 RMB with sales > 1000.

## Tools
- launch("com.taobao.taobao")
- tap / type — search bar + query
- swipe — scroll the results
- snapshot — read each item card (title, price, sales)

## Selection logic
1. Collect first 20 visible results
2. Filter by: valid price band, sales >= 1000
3. Sort by (price asc, sales desc)
4. Return top 3 with: title, price, sales, shop name
